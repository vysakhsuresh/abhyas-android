package com.layerbit.abhyas.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, run against the schemas Room exported for the versions they come from.
 *
 * This is the one test in the project whose absence could cost somebody everything they had. A
 * migration that produces a column Room does not expect throws IllegalStateException on the next
 * launch, and because the app ships no destructive fallback - deliberately - the database then
 * cannot be opened at all. The collection is still on disk and completely unreachable.
 *
 * MIGRATION_2_3 is also the only arithmetic in the app that runs exactly once per collection, over
 * data that is gone afterwards. If it converts wrongly there is no second attempt and nothing left
 * to compare against, so it is checked here value by value rather than just for not throwing.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AbhyasDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * Open the database at the current version through Room, which validates the schema the
     * migrations produced against the one Room expects and fails if they disagree.
     */
    private fun migrateToLatest() = helper.runMigrationsAndValidate(
        DATABASE_NAME,
        DATABASE_VERSION,
        true,
        // The app's own migration list, not a copy of it: a migration added to the app and forgotten
        // here would be precisely the one that went untested.
        *AbhyasDatabase.MIGRATIONS
    )

    private fun seedDeck(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO decks (id, name, createdAt, lastUsedAt, script) " +
                "VALUES (1, 'Biology', 1000, 2000, 'LATIN')"
        )
    }

    /** A v2 card row. v2 has no stability, difficulty or lastReviewedAt - that is what 2->3 adds. */
    private fun seedCard(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        front: String,
        state: String,
        dueAt: Long,
        intervalDays: Int,
        easeFactor: Double,
        lapses: Int = 0,
        learningStep: Int = 0
    ) {
        db.execSQL(
            "INSERT INTO cards (id, deckId, front, back, sourceText, state, dueAt, intervalDays, " +
                "easeFactor, repetitions, lapses, learningStep, suspended, createdAt) VALUES " +
                "($id, 1, '$front', 'A', NULL, '$state', $dueAt, $intervalDays, " +
                "$easeFactor, 4, $lapses, $learningStep, 0, 1000)"
        )
    }

    @Test
    fun migratingFromTwoToThreeKeepsEveryCard() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedDeck(db)
            seedCard(db, 1, "studied", "REVIEW", 100 * DAY, 45, 2.5)
            seedCard(db, 2, "unseen", "NEW", 0, 0, 2.5)
            seedCard(db, 3, "learning", "LEARNING", 100 * DAY, 0, 2.5, learningStep = 1)
        }

        migrateToLatest().use { db ->
            db.query("SELECT COUNT(*) FROM cards").use {
                it.moveToFirst()
                assertEquals("no card may be lost to a schema change", 3, it.getInt(0))
            }
        }
    }

    @Test
    fun aStudiedCardIsConvertedFromItsOldIntervalAndEase() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedDeck(db)
            // Due at day 100 on a 45-day interval, so it was last answered at day 55.
            seedCard(db, 1, "studied", "REVIEW", 100 * DAY, 45, 2.5)
        }

        migrateToLatest().use { db ->
            db.query(
                "SELECT stability, difficulty, lastReviewedAt, easeFactor FROM cards WHERE id = 1"
            ).use {
                it.moveToFirst()

                assertEquals("stability comes from the old interval", 45.0, it.getDouble(0), 0.001)
                // 2.5 is SM-2's default ease, which lands near the easy end of FSRS's 1..10 range.
                assertEquals("difficulty comes from the old ease", 1.6154, it.getDouble(1), 0.001)
                // Reconstructed rather than left at zero: dueAt minus the interval is the review time.
                // Without this the scheduler falls back to treating the interval as the elapsed time,
                // which withholds credit from every overdue card in a migrated collection.
                assertEquals(
                    "the last review is recoverable from dueAt and the interval",
                    (100 - 45) * DAY,
                    it.getLong(2)
                )
                assertEquals(
                    "the old ease is kept so the conversion stays auditable",
                    2.5,
                    it.getDouble(3),
                    0.001
                )
            }
        }
    }

    @Test
    fun aHarderCardConvertsToAHigherDifficultyThanAnEasierOne() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedDeck(db)
            // 1.3 is SM-2's ease floor - a card the user kept forgetting.
            seedCard(db, 1, "hard", "REVIEW", 100 * DAY, 10, 1.3, lapses = 6)
            seedCard(db, 2, "easy", "REVIEW", 100 * DAY, 10, 2.6)
        }

        migrateToLatest().use { db ->
            db.query("SELECT difficulty FROM cards ORDER BY id").use {
                it.moveToFirst()
                val hard = it.getDouble(0)
                it.moveToNext()
                val easy = it.getDouble(0)

                assertTrue("a card the user kept forgetting must convert harder", hard > easy)
                assertTrue("difficulty must stay inside FSRS's range, was $hard", hard in 1.0..10.0)
                assertTrue("difficulty must stay inside FSRS's range, was $easy", easy in 1.0..10.0)
            }
        }
    }

    @Test
    fun anUnseenCardIsLeftUntracked() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedDeck(db)
            seedCard(db, 1, "unseen", "NEW", 0, 0, 2.5)
        }

        migrateToLatest().use { db ->
            db.query("SELECT stability, difficulty FROM cards WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("a card never answered has no memory state", 0.0, it.getDouble(0), 0.0)
                assertEquals("a card never answered has no memory state", 0.0, it.getDouble(1), 0.0)
            }
        }
    }

    @Test
    fun aCardCaughtMidLearningIsLeftUntracked() {
        // Its own test because this is where the migration was wrong. A learning card has an interval
        // of 0, and converting it anyway gave it a stability of 0.01 days - and any non-zero stability
        // reads as a tracked card, so the scheduler ran the full model on that 0.01 rather than giving
        // the card the starting values it should have had. Left untouched, its next answer initialises
        // it properly.
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedDeck(db)
            seedCard(db, 1, "learning", "LEARNING", 100 * DAY, 0, 2.5, learningStep = 1)
        }

        migrateToLatest().use { db ->
            db.query("SELECT stability, difficulty, learningStep FROM cards WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("a learning card has no interval to convert", 0.0, it.getDouble(0), 0.0)
                assertEquals("a learning card has no interval to convert", 0.0, it.getDouble(1), 0.0)
                assertEquals("its place in the learning steps is kept", 1, it.getInt(2))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test.db"

        /** Must track the version on AbhyasDatabase's @Database annotation. */
        const val DATABASE_VERSION = 3

        const val DAY = 86_400_000L
    }
}
