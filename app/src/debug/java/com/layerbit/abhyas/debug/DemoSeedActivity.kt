package com.layerbit.abhyas.debug

import android.app.Activity
import android.os.Bundle
import androidx.room.withTransaction
import com.layerbit.abhyas.data.AppPreferences
import com.layerbit.abhyas.data.db.AbhyasDatabase
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.db.ReviewLogEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.occlusion.ImageStore
import com.layerbit.abhyas.data.occlusion.Occlusion
import com.layerbit.abhyas.data.occlusion.Region
import com.layerbit.abhyas.data.ocr.ScriptOption
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Debug builds only: fills an empty install with a believable collection for store screenshots.
 *
 * `adb shell am start -n com.layerbit.abhyas/.debug.DemoSeedActivity`
 *
 * Lives in the debug source set, so it is not in the release APK at all. Refuses to run on a
 * collection that already has decks, so it can never be pointed at someone's real data.
 */
class DemoSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AbhyasDatabase.get(this)
        runBlocking {
            if (db.deckDao().all().isEmpty()) seed(db)
        }
        AppPreferences(this).introSeen = intent.getBooleanExtra("introSeen", true)
        finish()
    }

    private suspend fun seed(db: AbhyasDatabase) = db.withTransaction {
        val decks = db.deckDao()
        val cards = db.cardDao()
        val log = db.reviewLogDao()
        val random = Random(7)
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        suspend fun deck(name: String, used: Long, script: ScriptOption = ScriptOption.LATIN, parent: Long? = null) =
            decks.insert(DeckEntity(name = name, createdAt = now - 30 * day, lastUsedAt = now - used, script = script, parentId = parent))

        val biology = deck("Biology", used = 2 * 60_000)
        val cell = deck("Ch 4 · The Cell", used = 60_000, parent = biology)
        val respiration = deck("Ch 5 · Respiration", used = 3 * 3_600_000, parent = biology)
        val history = deck("Modern Indian History", used = 5 * 3_600_000)
        val hindi = deck("हिन्दी व्याकरण", used = day, script = ScriptOption.DEVANAGARI)
        val physics = deck("Physics · Laws of Motion", used = 2 * day)
        val anatomy = deck("Anatomy diagrams", used = 3 * day)

        /** A studied card, due [dueInDays] from now (negative = overdue). */
        fun reviewed(deckId: Long, front: String, back: String, source: String?, dueInDays: Double, stability: Double, tags: String = "") =
            CardEntity(
                deckId = deckId, front = front, back = back, sourceText = source,
                state = CardState.REVIEW, dueAt = now + (dueInDays * day).toLong(),
                intervalDays = stability.toInt().coerceAtLeast(1), stability = stability,
                difficulty = 4.0 + random.nextDouble() * 3, lastReviewedAt = now - (stability * day).toLong(),
                repetitions = 3, createdAt = now - 20 * day, tags = tags
            )

        fun fresh(deckId: Long, front: String, back: String, source: String?, tags: String = "") =
            CardEntity(deckId = deckId, front = front, back = back, sourceText = source, createdAt = now - day, tags = tags)

        val all = mutableListOf<CardEntity>()
        all += reviewed(cell, "What is the function of ribosomes?", "Protein synthesis",
            "Ribosomes: the site of protein synthesis in the cell.", -0.2, 8.5, "exam")
        all += reviewed(cell, "What is the cell membrane made of?", "A phospholipid bilayer with embedded proteins",
            "The cell membrane is made of a phospholipid bilayer with embedded proteins.", -0.1, 12.0, "exam")
        all += reviewed(cell, "Mitochondria", "Releases energy from food as ATP",
            "Mitochondria - releases energy from food as ATP", -0.05, 5.0, "exam")
        all += reviewed(cell, "The ____ controls what enters and leaves the cell.", "cell membrane",
            "The cell membrane controls what enters and leaves the cell.", 2.0, 4.0)
        all += fresh(cell, "Golgi apparatus", "Packages and sends out proteins", "Golgi apparatus | Packages and sends out proteins", "exam")
        all += fresh(cell, "Lysosome", "Breaks down waste with enzymes", "Lysosome | Breaks down waste with enzymes", "exam")
        all += fresh(cell, "Vacuole", "Stores water, food and waste", "Vacuole | Stores water, food and waste")
        all += fresh(cell, "Name the three parts of the cell theory.", "All living things are made of cells; the cell is the basic unit of life; cells come from cells",
            "Cell theory: all living things are made of cells; the cell is the basic unit of life; cells come from cells.")

        all += reviewed(respiration, "What is the word equation for aerobic respiration?", "Glucose + oxygen → carbon dioxide + water (+ energy)", null, 1.0, 6.0)
        all += reviewed(respiration, "Where does glycolysis happen?", "In the cytoplasm", "Glycolysis happens in the cytoplasm.", 3.0, 9.0)
        all += fresh(respiration, "Anaerobic respiration in muscles produces ____.", "lactic acid", "Anaerobic respiration in muscles produces lactic acid.")

        val historyCards = listOf(
            Triple("When was the Battle of Plassey?", "1757", 4.0),
            Triple("Who founded the Indian National Congress?", "A. O. Hume, in 1885", 7.0),
            Triple("What was the Dandi March protesting?", "The British salt tax", -0.3),
            Triple("When did the Quit India Movement begin?", "August 1942", 10.0),
            Triple("Who wrote 'Gitanjali'?", "Rabindranath Tagore", 1.0)
        )
        historyCards.forEach { (q, a, due) -> all += reviewed(history, q, a, null, due, 3.0 + random.nextDouble() * 15, "exam") }
        all += fresh(history, "What did the Rowlatt Act allow?", "Detention without trial", "The Rowlatt Act (1919) allowed detention without trial.")

        all += reviewed(hindi, "संज्ञा किसे कहते हैं?", "किसी व्यक्ति, वस्तु, स्थान या भाव के नाम को", null, -0.1, 5.0)
        all += reviewed(hindi, "सर्वनाम", "संज्ञा के स्थान पर आने वाला शब्द", null, 2.0, 8.0)
        all += fresh(hindi, "विशेषण किसे कहते हैं?", "संज्ञा या सर्वनाम की विशेषता बताने वाले शब्द को", null)

        all += reviewed(physics, "State Newton's first law.", "A body stays at rest or in uniform motion unless acted on by a net force", null, 6.0, 14.0)
        all += reviewed(physics, "F = ?", "ma (mass × acceleration)", null, 0.6, 4.0)
        all += reviewed(physics, "What is inertia?", "The tendency of a body to resist a change in its state of motion", null, 9.0, 20.0)

        cards.insertAll(all)

        // A labelled diagram for the picture cards, copied out of the debug assets.
        val imageName = "demo0001-heart.jpg"
        runCatching {
            val bytes = assets.open("demo-diagram.jpg").use { it.readBytes() }
            ImageStore.restore(this@DemoSeedActivity, imageName, bytes)
            val regions = listOf(
                Region(0.03f, 0.13f, 0.30f, 0.22f, "Aorta"),
                Region(0.70f, 0.13f, 0.97f, 0.22f, "Pulmonary artery"),
                Region(0.03f, 0.43f, 0.30f, 0.52f, "Right atrium"),
                Region(0.70f, 0.43f, 0.97f, 0.52f, "Left atrium"),
                Region(0.03f, 0.73f, 0.30f, 0.82f, "Right ventricle"),
                Region(0.70f, 0.73f, 0.97f, 0.82f, "Left ventricle")
            )
            cards.insertAll(regions.indices.map { i ->
                CardEntity(
                    deckId = anatomy, front = "The heart", back = regions[i].label, sourceText = null,
                    createdAt = now - day + i, type = CardType.OCCLUSION, imagePath = imageName,
                    occlusion = Occlusion(regions, i).encode(), tags = "anatomy"
                )
            })
        }

        // Twenty-three days of study, today included, at about 89% recall.
        val ids = (1L..all.size.toLong()).toList()
        for (d in 22 downTo 0) {
            val count = 14 + random.nextInt(24)
            repeat(count) {
                val grade = when (random.nextInt(100)) {
                    in 0..10 -> Grade.AGAIN
                    in 11..20 -> Grade.HARD
                    in 21..90 -> Grade.GOOD
                    else -> Grade.EASY
                }
                log.insert(
                    ReviewLogEntity(
                        cardId = ids.random(random), deckId = cell,
                        reviewedAt = now - d * day - random.nextLong(3_600_000L),
                        grade = grade, intervalBefore = 3, intervalAfter = 7
                    )
                )
            }
        }
    }
}
