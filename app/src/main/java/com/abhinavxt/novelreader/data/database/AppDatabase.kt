package com.abhinavxt.novelreader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ReaderSettingsEntity::class,
        BookmarkEntity::class,
        PronunciationEntry::class,
        ReadingStatEvent::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun readerSettingsDao(): ReaderSettingsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun pronunciationDao(): PronunciationDao
    abstract fun readingStatDao(): ReadingStatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_reader_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}