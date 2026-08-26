package com.remnant.dreams.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Room schema version, pinned by `DreamDatabaseVersionTest` so a bump cannot ship
 * unmigrated. Read that test before changing this number -- it spells out the procedure.
 */
const val DREAM_DB_VERSION = 2

@Database(entities = [DreamEntry::class], version = DREAM_DB_VERSION, exportSchema = true)
abstract class DreamDatabase : RoomDatabase() {

    abstract fun dreamDao(): DreamDao

    companion object {
        @Volatile
        private var INSTANCE: DreamDatabase? = null

        fun getInstance(context: Context): DreamDatabase {
            return INSTANCE ?: synchronized(this) {
                // No destructive fallback: any future version bump MUST ship a
                // hand-written Migration, or Room throws rather than wiping journals.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DreamDatabase::class.java,
                    "remnant_dreams.db"
                )
                    .build().also { INSTANCE = it }
            }
        }
    }
}
