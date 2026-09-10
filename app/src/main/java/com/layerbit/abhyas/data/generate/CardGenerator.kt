package com.layerbit.abhyas.data.generate

import com.layerbit.abhyas.data.ocr.PageText

/**
 * Turns a read page into proposed cards.
 *
 * This is an interface with exactly one implementation on purpose. Card writing is the part of
 * Abhyas most obviously improved by a language model, and the day that becomes worth its cost -
 * a paid tier, or a small on-device model that is fast enough - it slots in here and nothing
 * else in the app has to know. [HeuristicCardGenerator] is what ships in the meantime, and it
 * runs on-device for free, which is what keeps the no-network guarantee intact.
 */
fun interface CardGenerator {
    suspend fun generate(page: PageText): List<CardCandidate>
}
