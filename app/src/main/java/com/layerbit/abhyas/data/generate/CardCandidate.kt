package com.layerbit.abhyas.data.generate

/** How a candidate was arrived at. Shown as a chip on the review screen. */
enum class CardKind(val label: String) {
    /** The page already contained a question and its answer. */
    QA("Q&A"),

    /** "X: y" or "X is y" - the page defined a term. */
    DEFINITION("Definition"),

    /** A heading and the items under it. */
    LIST("List"),

    /** A sentence with one significant value blanked out. */
    CLOZE("Fill in the blank")
}

/**
 * A proposed card, before the user has agreed to it.
 *
 * Nothing generated here reaches a deck without being shown first. The heuristics below are good
 * enough to save nearly all of the typing and nowhere near good enough to be trusted silently -
 * so the contract of this whole package is *suggestions*, and the review screen is where they
 * become cards.
 */
data class CardCandidate(
    val front: String,
    val back: String,
    /** The sentence this came from, kept on the card so the user can always see the context. */
    val sourceText: String,
    val kind: CardKind,
    /**
     * How likely this is to be a good card, 0..1. Only ever used for ordering - the best
     * suggestions belong at the top of the review screen, where someone skimming will see them.
     */
    val confidence: Float
)
