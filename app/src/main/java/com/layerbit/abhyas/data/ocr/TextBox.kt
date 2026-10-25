package com.layerbit.abhyas.data.ocr

/**
 * Where a block of text sits on the page, in image pixels.
 *
 * A plain data class rather than `android.graphics.Rect` so the layout reasoning that depends on
 * it - which blocks are consecutive lines of one paragraph, which are unrelated furniture - can
 * be unit-tested without an emulator. [PageTextReader] converts ML Kit's Rect into this.
 */
data class TextBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** How much of the narrower box's width is shared with the other, 0..1. */
    fun horizontalOverlapWith(other: TextBox): Float {
        val overlap = minOf(right, other.right) - maxOf(left, other.left)
        if (overlap <= 0) return 0f
        val narrower = minOf(width, other.width)
        return if (narrower <= 0) 0f else overlap.toFloat() / narrower
    }

    /** Vertical space between the bottom of this box and the top of one below it. */
    fun gapBelow(other: TextBox): Int = other.top - bottom

    /**
     * How much of the shorter box's height is shared with the other, 0..1.
     *
     * The test for "these two pieces of text are on the same row", which is what pairs a table's
     * left cell with its right one. Measured against the shorter box so a tall cell does not
     * dilute the overlap of a short one sitting squarely beside it.
     */
    fun verticalOverlapWith(other: TextBox): Float {
        val overlap = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (overlap <= 0) return 0f
        val shorter = minOf(height, other.height)
        return if (shorter <= 0) 0f else overlap.toFloat() / shorter
    }

    /** The smallest box holding both. */
    fun union(other: TextBox): TextBox = TextBox(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom)
    )
}

/**
 * One block of recognised text: its lines, and where it was.
 *
 * The box is nullable because ML Kit does not guarantee one. Everything that uses it treats a
 * missing box as "do not assume anything about the layout", which is the safe reading.
 *
 * [lineBoxes] is where each line sat, parallel to [lines]. A block box alone cannot tell a table
 * from a paragraph: ML Kit routinely returns a two-column table as one block whose lines
 * alternate term, definition, term, definition, and only the boxes of the individual lines say
 * which side of the page each one was on. Null when any line came back without a box - a list
 * with holes in it would invite the layout code to guess, and guessing is what it must not do.
 */
data class PageBlock(
    val lines: List<String>,
    val box: TextBox? = null,
    val lineBoxes: List<TextBox>? = null
) {
    /**
     * Where line [index] sat, or null when that cannot be known.
     *
     * A one-line block's box *is* its line's box, which is how a table whose every cell came back
     * as a block of its own - the commonest delivery for short cells - can be read without
     * per-line boxes at all.
     */
    fun boxOfLine(index: Int): TextBox? =
        lineBoxes?.takeIf { it.size == lines.size }?.getOrNull(index)
            ?: box?.takeIf { lines.size == 1 && index == 0 }
}

/** One line of a page, addressed by its block and its position within that block. */
data class LineRef(val block: Int, val line: Int)
