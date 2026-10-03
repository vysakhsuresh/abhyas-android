package com.layerbit.abhyas.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeckEntity::class, CardEntity::class, ReviewLogEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AbhyasDatabase : RoomDatabase() {

    abstract fun deckDao(): DeckDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao

    companion object {
        @Volatile
        private var instance: AbhyasDatabase? = null

        /**
         * v1 -> v2: decks gained the script their pages are written in.
         *
         * Existing decks become LATIN, which is what they were already being read as, so nothing
         * changes for anyone until they choose otherwise. Written out by hand rather than left to
         * a destructive fallback: a collection is months of work, and losing it to a schema bump
         * is not a trade this app is ever allowed to make.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE decks ADD COLUMN script TEXT NOT NULL DEFAULT 'LATIN'"
                )
            }
        }

        /**
         * v2 -> v3: SM-2 gives way to FSRS.
         *
         * The new columns are added, then every card that has actually been studied is converted
         * in place: its interval becomes its stability, and its ease factor becomes a difficulty.
         * That mapping is an approximation and is allowed to be - the alternative was resetting
         * every card in every collection to new, throwing away exactly the history being
         * reconstructed. See [Fsrs.fromSuperMemo] for the reasoning.
         *
         * The conversion is written as SQL rather than loading rows into Kotlin because a
         * migration runs before the DAOs exist, and because it has to finish inside the one
         * transaction Room gives it.
         *
         * easeFactor is kept rather than dropped. It costs eight bytes a card and it is the only
         * record of what the old scheduler believed; dropping it would make this migration
         * impossible to check after the fact.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN stability REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE cards ADD COLUMN difficulty REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE cards ADD COLUMN lastReviewedAt INTEGER NOT NULL DEFAULT 0")

                // Stability from the old interval; difficulty from the old ease, mapping SM-2's
                // 2.6..1.3 range onto FSRS's 1..10 and clamped at both ends.
                //
                // lastReviewedAt is reconstructed rather than left at zero, and it is exactly
                // recoverable: SM-2 set dueAt to the review time plus the interval, so subtracting
                // the interval gives the review time back. Without it the scheduler falls back to
                // treating the interval as the elapsed time, which silently withholds credit from
                // every overdue card in a migrated collection - recalling something after six weeks
                // when it was scheduled for two would be scored as though only two had passed.
                //
                // `intervalDays > 0` rather than `state != 'NEW'` is what keeps a card that was
                // mid-learning out of this. Its interval is zero, so the old expression handed it a
                // stability of 0.01 days - and because any non-zero stability reads as "tracked",
                // the scheduler then ran the full FSRS model on that 0.01 instead of giving the
                // card the starting values it should have had. Left untouched, such a card is
                // untracked, and its next answer initialises it properly.
                db.execSQL(
                    """
                    UPDATE cards
                       SET stability = CAST(intervalDays AS REAL),
                           difficulty = MIN(10.0, MAX(1.0,
                               1.0 + MIN(1.0, MAX(0.0, (2.6 - easeFactor) / 1.3)) * 8.0)),
                           lastReviewedAt =
                               MAX(0, dueAt - CAST(intervalDays AS INTEGER) * 86400000)
                     WHERE state != 'NEW' AND intervalDays > 0
                    """
                )
            }
        }

        /**
         * Every migration, in one place so the builder below and the migration tests cannot disagree
         * about which ones exist. A migration present here but forgotten in a test would be exactly
         * the one that goes unchecked.
         */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun get(context: Context): AbhyasDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AbhyasDatabase::class.java,
                "abhyas.db"
            )
                // No fallbackToDestructiveMigration: a user's collection is months of work and
                // must never be dropped to satisfy a schema bump. Every future version ships a
                // real migration, and the exported schemas under app/schemas are what they get
                // written against.
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
