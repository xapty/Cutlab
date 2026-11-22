package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, MediaAssetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CutLabDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        @Volatile
        private var instance: CutLabDatabase? = null

        fun getInstance(context: Context): CutLabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CutLabDatabase::class.java,
                    "cutlab_studio.db",
                ).build().also { instance = it }
            }
        }
    }
}
