package com.layerbit.abhyas.data.model

/**
 * What a card *is*, as opposed to how it was suggested.
 *
 * Kept apart from the generator's `CardKind`, which only ever describes a suggestion on the review
 * screen and is never stored. This one is stored, and it decides how the study screen draws the card.
 */
enum class CardType {
    /** Words on both sides. Every card made from a page of text. */
    BASIC,

    /**
     * A photographed diagram with one region masked out. The front is the picture with the mask on,
     * the back is the same picture with the region revealed. See `data/occlusion`.
     */
    OCCLUSION
}
