package com.abhinavxt.novelreader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ReaderSettingsEntity::class,
        BookmarkEntity::class,
        PronunciationEntry::class,
        ReadingStatEvent::class,
        HighlightEntity::class           // NEW in v9
    ],
    version = 9,
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
    abstract fun highlightDao(): HighlightDao            // NEW in v9

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // #1: Proper migration instead of destructive
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pronunciation_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word TEXT NOT NULL,
                        replacement TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_stats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        novelId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        wordsRead INTEGER NOT NULL,
                        readingTimeMs INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Migration v8 → v9 adds:
         *  1. highlights table — for text highlighting & annotations
         *  2. reader_settings new columns — readingMode, pageTransition, margins, etc.
         *  3. novels.previousTotalChapters — for update changelog badges
         *
         * All new columns have defaults so existing rows are unaffected.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 1. Create highlights table ──────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS highlights (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        novelId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        chapterNumber INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        paragraphIndex INTEGER NOT NULL,
                        startOffset INTEGER NOT NULL,
                        endOffset INTEGER NOT NULL,
                        selectedText TEXT NOT NULL,
                        note TEXT,
                        color TEXT NOT NULL DEFAULT 'YELLOW',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // ── 2. Add new reader_settings columns ──────────────
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN readingMode TEXT NOT NULL DEFAULT 'SCROLL'")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN pageTransition TEXT NOT NULL DEFAULT 'SLIDE'")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN horizontalMargin INTEGER NOT NULL DEFAULT 16")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN keepScreenOn INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN volumeKeyNavigation INTEGER NOT NULL DEFAULT 0")

                // ── 3. Add novels.previousTotalChapters ─────────────
                // Default to current totalChapters so existing novels don't show
                // a fake "X new chapters" badge on first launch after update.
                db.execSQL("ALTER TABLE novels ADD COLUMN previousTotalChapters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE novels SET previousTotalChapters = totalChapters")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_reader_database"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                    // No fallbackToDestructiveMigration() — if a migration is missing,
                    // the app crashes instead of silently wiping the user's library,
                    // bookmarks, reading progress, and stats. Always write migrations.
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}