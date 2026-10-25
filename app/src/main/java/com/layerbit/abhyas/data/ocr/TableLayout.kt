package com.layerbit.abhyas.data.ocr

/** One row of a term table: the left cell, the right cell, and the page lines they came from. */
data class TableRow(val term: String, val answer: String, val lines: Set<LineRef>)

/**
 * A two-column table found on the page.
 *
 * [lines] covers every line the table occupies, header included, so the passes that run after
 * it can be handed a page with the table taken out. A header row is consumed but yields no row:
 * it is part of the table, not a fact in it.
 */
data class PageTable(val rows: List<TableRow>, val lines: Set<LineRef>)

/**
 * Finds the two-column term/answer tables on a page - "Mitochondria | Powerhouse of the cell" -
 * from the boxes of the lines alone.
 *
 * Textbooks print these constantly, and without this they read as scrambled prose. ML Kit hands a
 * table back either as one block per column - every term, then every answer - or as one block
 * whose lines alternate between the two sides, and in neither case does reading order pair a term
 * with its answer. Only where the lines sat does. So this works on lines rather than blocks,
 * ignores the order they arrived in entirely, and pairs them by the rows they share on the page.
 *
 * It is deliberately conservative, because the geometry of a table is also the geometry of an
 * ordinary two-column page: a newspaper-style layout puts two lines of prose side by side on every
 * row, left-aligned in two clusters with a gutter between them, which is a table in every respect
 * the boxes can measure. Pairing those produces confident nonsense - half a sentence as the
 * question and the neighbouring column's half-sentence as its answer - which is far worse than
 * the prose cards the page would otherwise get. So the geometry is only half the test. The other
 * half is that every left cell must read as a term rather than a fragment of a sentence, and a
 * table that fails that anywhere is rejected whole rather than trimmed: three rows that happen to
 * pass out of a column of prose are still prose.
 *
 * What it requires:
 *   - at least [MIN_ROWS] rows after any header, each exactly two cells on one line of the page
 *   - the left cells left-aligned with each other, and likewise the right cells
 *   - a clear gutter between every left cell and the right column
 *   - consecutive rows close enough together to be one table
 *
 * A right cell that wraps continues onto lines that sit under the right column with nothing in
 * the left one, and those are joined onto the row above. A left cell that wraps is not handled:
 * the table ends there, which loses cards but never pairs the wrong ones.
 *
 * Lines without a box are invisible to all of this. A page read with no layout information has
 * no tables, which is the honest reading of "the layout is unknown".
 */
internal class TableLayout(private val page: PageText) {

    private val profile = page.profile

    /** A line and where it sat. */
    private class Placed(val ref: LineRef, val text: String, val box: TextBox)

    /** A row being built: its left cell, and the right cell's line plus any wrapped lines. */
    private class Row(val left: Placed, val right: MutableList<Placed>) {
        val bottom: Int get() = maxOf(left.box.bottom, right.maxOf { it.box.bottom })
    }

    /** A run of rows that line up into one table, before it has been judged. */
    private class Candidate(val leftX: Int, val rightX: Int) {
        val rows = mutableListOf<Row>()
        val bottom: Int get() = rows.last().bottom
        /** The furthest any left cell reaches, so the gutter is measured against the widest term. */
        var leftReach: Int = Int.MIN_VALUE
    }

    fun find(): List<PageTable> {
        val placed = placedLines()
        if (placed.size < MIN_ROWS * 2) return emptyList()

        // Every tolerance is in line heights, so the same rules work for a close-up and a whole
        // page shot from arm's length.
        val lineHeight = placed.map { it.box.height }.sorted()[placed.size / 2].toFloat()
        if (lineHeight <= 0f) return emptyList()

        val tables = mutableListOf<PageTable>()
        var current: Candidate? = null

        fun close() {
            current?.let { judge(it)?.let(tables::add) }
            current = null
        }

        for (row in visualRows(placed)) {
            val open = current
            when {
                row.size == 2 && hasGutter(row[0], row[1], lineHeight) -> {
                    val (left, right) = row
                    if (open == null || !fitsRow(open, left, right, lineHeight)) {
                        close()
                        current = Candidate(left.box.left, right.box.left)
                    }
                    current!!.apply {
                        rows += Row(left, mutableListOf(right))
                        leftReach = maxOf(leftReach, left.box.right)
                    }
                }
                row.size == 1 && open != null && fitsWrap(open, row[0], lineHeight) ->
                    open.rows.last().right += row[0]
                else -> close()
            }
        }
        close()
        return tables
    }

    // ------------------------------------------------------------------------------- geometry

    /** Every line on the page that has a known position. */
    private fun placedLines(): List<Placed> =
        page.blocks.flatMapIndexed { b, block ->
            block.lines.indices.mapNotNull { i ->
                val box = block.boxOfLine(i) ?: return@mapNotNull null
                if (box.width <= 0 || box.height <= 0) return@mapNotNull null
                Placed(LineRef(b, i), block.lines[i].trim(), box).takeIf { it.text.isNotEmpty() }
            }
        }

    /**
     * Group lines into the rows of the page, left to right within each.
     *
     * Each line is compared with the row's *first* line rather than its latest, so a row cannot
     * creep downwards one slightly lower line at a time until it has swallowed the row beneath.
     */
    private fun visualRows(placed: List<Placed>): List<List<Placed>> {
        val rows = mutableListOf<MutableList<Placed>>()
        placed.sortedWith(compareBy({ it.box.top }, { it.box.left })).forEach { line ->
            val row = rows.lastOrNull()
            if (row != null && row.first().box.verticalOverlapWith(line.box) >= MIN_ROW_OVERLAP) {
                row += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.map { row -> row.sortedBy { it.box.left } }
    }

    /** Two cells with a real gutter between them, not two words of one line read apart. */
    private fun hasGutter(left: Placed, right: Placed, lineHeight: Float): Boolean =
        right.box.left - left.box.right >= lineHeight * MIN_GUTTER_LINES

    private fun fitsRow(table: Candidate, left: Placed, right: Placed, lineHeight: Float): Boolean {
        val tolerance = lineHeight * ALIGN_TOLERANCE_LINES
        val gutter = lineHeight * MIN_GUTTER_LINES
        val top = minOf(left.box.top, right.box.top)
        return kotlin.math.abs(left.box.left - table.leftX) <= tolerance &&
            kotlin.math.abs(right.box.left - table.rightX) <= tolerance &&
            // The gutter holds for the whole column, not just this row: a long term reaching
            // under the right column means these are not two columns at all.
            table.rightX - left.box.right >= gutter &&
            right.box.left - table.leftReach >= gutter &&
            top - table.bottom <= lineHeight * MAX_ROW_GAP_LINES
    }

    /**
     * A line under the right column with nothing beside it, directly below the last row: the
     * answer wrapped. Capped, because a right column that runs on for a paragraph is a label
     * beside a block of text rather than a table cell.
     */
    private fun fitsWrap(table: Candidate, line: Placed, lineHeight: Float): Boolean {
        val last = table.rows.last()
        return last.right.size <= MAX_WRAPPED_LINES &&
            kotlin.math.abs(line.box.left - table.rightX) <= lineHeight * ALIGN_TOLERANCE_LINES &&
            line.box.left - table.leftReach >= lineHeight * MIN_GUTTER_LINES &&
            line.box.top - last.bottom <= lineHeight * MAX_WRAP_GAP_LINES
    }

    // -------------------------------------------------------------------------------- content

    /** Decide whether an aligned run of rows is really a table, and read it if so. */
    private fun judge(table: Candidate): PageTable? {
        val header = table.rows.first().takeIf { looksLikeHeader(it, table.rows) }
        val data = table.rows.filter { it !== header }
        if (data.size < MIN_ROWS) return null

        val terms = data.map { it.left }
        if (looksLikeProse(terms)) return null

        val rows = data.map { row ->
            TableRow(
                term = cleanTerm(row.left.text),
                answer = joinWrapped(row.right.map { it.text }),
                lines = (row.right.map { it.ref } + row.left.ref).toSet()
            )
        }
        val lines = table.rows.flatMapTo(mutableSetOf()) { row ->
            row.right.map { it.ref } + row.left.ref
        }
        return PageTable(rows, lines)
    }

    /**
     * Whether the left column is a column of prose rather than of terms.
     *
     * Every left cell has to be term-shaped: short, and free of anything a term does not carry -
     * a sentence ending, a comma, a word broken across the line. One failure rejects the table.
     *
     * Then a second test for the prose that passes the first, which narrow newspaper columns do:
     * "The council met on Tuesday to" is six words with no punctuation. Prose fills its column, so
     * every line comes out nearly the same width, whereas terms are ragged. A full column alone is
     * not damning - three-character Chinese terms are all exactly as wide as each other - so it
     * needs company: lines that start lowercase in a script that has case, or lines too long to
     * be the short terms a table is made of.
     */
    private fun looksLikeProse(terms: List<Placed>): Boolean {
        if (terms.any { !isTermShaped(it.text) }) return true

        val widest = terms.maxOf { it.box.width }
        val full = terms.all { it.box.width >= widest * FULL_COLUMN_FRACTION }
        if (!full) return false

        val lowercase = terms.count { term -> term.text.firstOrNull { it.isLetter() }?.isLowerCase() == true }
        if (lowercase * 2 >= terms.size) return true

        val units = terms.map { profile.units(cleanTerm(it.text)) }.sorted()
        return units[units.size / 2] * 2 > profile.maxTermUnits
    }

    private fun isTermShaped(text: String): Boolean {
        val term = cleanTerm(text)
        if (term.isEmpty()) return false
        if (profile.units(term) !in 1..profile.maxTermUnits) return false
        // A trailing colon has already been stripped, so any terminator left is mid-thought.
        if (term.any { it in profile.sentenceEnders }) return false
        if (term.any { it in CLAUSE_PUNCTUATION }) return false
        return term.last() !in HYPHENS
    }

    /**
     * Whether [row], the first of the table, is its column headings.
     *
     * Either cell being a known heading word is enough - "Organelle | Function" names its left
     * column specifically but its right one generically. Otherwise a header is the one row styled
     * differently: in capitals, or in Title Case where no row below it is. Title Case on its own
     * proves nothing in a table whose every row is capitalised, and dropping the first real row
     * of that table would silently lose a card.
     */
    private fun looksLikeHeader(row: Row, rows: List<Row>): Boolean {
        val left = cleanTerm(row.left.text)
        val right = cleanTerm(joinWrapped(row.right.map { it.text }))
        if (row.right.size > 1) return false

        val words = profile.tableHeaderWords
        if (left.lowercase() in words || right.lowercase() in words) return true

        if (profile.units(left) > MAX_HEADER_UNITS || profile.units(right) > MAX_HEADER_UNITS) {
            return false
        }
        if (isAllCaps(left) && isAllCaps(right)) return true

        return isTitleCase(left) && isTitleCase(right) &&
            rows.drop(1).none { isTitleCase(it.left.text) && isTitleCase(it.right.first().text) }
    }

    private fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    private fun isTitleCase(text: String): Boolean {
        val words = text.split(ScriptProfile.WHITESPACE).filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { word -> word.first().isUpperCase() }
    }

    /** A term without the colon or bullet some tables print beside it. */
    private fun cleanTerm(text: String): String =
        text.trim().replaceFirst(PageText.BULLET, "").trim().trimEnd { it in COLONS }.trim()

    /**
     * Rejoin a wrapped right cell. A hyphen at a line end is a split word, as in the paragraph
     * joiner; otherwise the script's own joiner, so a Chinese cell does not gain a space mid-word.
     */
    private fun joinWrapped(lines: List<String>): String =
        lines.map { it.trim() }.reduce { joined, next ->
            if (joined.endsWith("-")) joined.dropLast(1) + next
            else joined + profile.joinerBetween(joined, next) + next
        }

    private companion object {
        /** Two rows could be a coincidence of layout; three is a pattern. */
        const val MIN_ROWS = 3

        /** How much two lines must share vertically to sit on one row of the page. */
        const val MIN_ROW_OVERLAP = 0.5f

        /** How far a cell's left edge may stray from its column's, in line heights. */
        const val ALIGN_TOLERANCE_LINES = 1.0f

        /**
         * The narrowest gutter that separates two columns, in line heights. A word space is a
         * fraction of a line height, so this cannot mistake one line read in two pieces for two
         * cells.
         */
        const val MIN_GUTTER_LINES = 1.0f

        /** Tables pad their rows, so more room than a paragraph's line spacing, but not a section break. */
        const val MAX_ROW_GAP_LINES = 2.5f

        /** A wrapped line sits under the line it continues, with no more than ordinary spacing. */
        const val MAX_WRAP_GAP_LINES = 1.0f

        /** A cell may wrap onto this many further lines. */
        const val MAX_WRAPPED_LINES = 2

        /** Every line at least this share of the widest is a column of text that fills its measure. */
        const val FULL_COLUMN_FRACTION = 0.75f

        /** Column headings are a word or two, never a phrase. */
        const val MAX_HEADER_UNITS = 3

        const val COLONS = ":：﹕"
        const val CLAUSE_PUNCTUATION = ",，、"
        const val HYPHENS = "-‐"
    }
}
