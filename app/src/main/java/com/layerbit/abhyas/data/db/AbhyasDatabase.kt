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
                db.execSQL(
                    """
                    UPDATE cards
                       SET stability = MAX(0.01, CAST(intervalDays AS REAL)),
                           difficulty = MIN(10.0, MAX(1.0,
                               1.0 + MIN(1.0, MAX(0.0, (2.6 - easeFactor) / 1.3)) * 8.0))
                     WHERE state != 'NEW'
                    """
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
