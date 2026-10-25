package com.layerbit.abhyas.data.occlusion

import org.json.JSONArray
import org.json.JSONObject

/**
 * One masked region of a picture, in fractions of the image's width and height (0..1).
 *
 * Fractions rather than pixels, so the same card draws correctly at any size the study screen lays
 * the picture out at, and survives the picture being re-encoded at a different resolution.
 */
data class Region(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** What the region hides - "Left ventricle". Becomes the card's answer. May be blank. */
    val label: String = ""
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Ordered and clamped into the picture, whichever corner the drag started from. */
    fun normalised(): Region = Region(
        left = minOf(left, right).coerceIn(0f, 1f),
        top = minOf(top, bottom).coerceIn(0f, 1f),
        right = maxOf(left, right).coerceIn(0f, 1f),
        bottom = maxOf(top, bottom).coerceIn(0f, 1f),
        label = label
    )

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Too small to be a deliberate drag - a tap that moved a little. */
    val isTooSmall: Boolean get() = width < MIN_SIDE || height < MIN_SIDE

    companion object {
        const val MIN_SIDE = 0.02f
    }
}

/**
 * The masks on one image-occlusion card: every region drawn on the picture, and which one this
 * card asks about.
 *
 * Every card made from one picture carries the full set, so each can show the user where the other
 * labels are - the style Anki calls "hide one, guess one". The other regions stay visible because a
 * diagram is learned in relation to its neighbours, and hiding all of them turns a picture of the
 * heart into a picture of saffron boxes.
 */
data class Occlusion(val regions: List<Region>, val target: Int) {

    val targetRegion: Region? get() = regions.getOrNull(target)

    fun encode(): String = JSONObject().apply {
        put("target", target)
        put("regions", JSONArray().apply {
            regions.forEach { r ->
                put(JSONObject().apply {
                    put("l", r.left.toDouble())
                    put("t", r.top.toDouble())
                    put("r", r.right.toDouble())
                    put("b", r.bottom.toDouble())
                    put("label", r.label)
                })
            }
        })
    }.toString()

    companion object {
        /** Null for anything unreadable, so one bad row costs one card its mask, not a crash. */
        fun decode(json: String?): Occlusion? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                val array = root.getJSONArray("regions")
                val regions = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Region(
                        left = o.getDouble("l").toFloat(),
                        top = o.getDouble("t").toFloat(),
                        right = o.getDouble("r").toFloat(),
                        bottom = o.getDouble("b").toFloat(),
                        label = o.optString("label", "")
                    ).normalised()
                }
                Occlusion(regions, root.getInt("target")).takeIf { it.targetRegion != null }
            }.getOrNull()
        }
    }
}
