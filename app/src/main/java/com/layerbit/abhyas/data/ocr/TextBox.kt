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
}

/**
 * One block of recognised text: its lines, and where it was.
 *
 * The box is nullable because ML Kit does not guarantee one. Everything that uses it treats a
 * missing box as "do not assume anything about the layout", which is the safe reading.
 */
data class PageBlock(
    val lines: List<String>,
    val box: TextBox? = null
)
