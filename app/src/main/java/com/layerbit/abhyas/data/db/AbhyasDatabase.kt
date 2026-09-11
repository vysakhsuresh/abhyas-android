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
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
