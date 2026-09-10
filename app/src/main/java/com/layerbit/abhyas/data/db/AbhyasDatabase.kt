package com.layerbit.abhyas.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DeckEntity::class, CardEntity::class, ReviewLogEntity::class],
    version = 1,
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
                .build()
                .also { instance = it }
        }
    }
}
