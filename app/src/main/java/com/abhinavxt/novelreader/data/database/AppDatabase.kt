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
        HighlightEntity::class,          // NEW in v9
        CategoryEntity::class,           // NEW in v12
        NovelCategoryCrossRef::class,    // NEW in v12
        UpdateEntity::class,             // NEW in v12
        ChapterFtsEntity::class          // NEW in v14 — full-text search index
    ],
    version = 14,
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
    abstract fun categoryDao(): CategoryDao              // NEW in v12
    abstract fun updateDao(): UpdateDao                  // NEW in v12

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

        /**
         * Migration v9 → v10: adds reader_settings.autoScrollSpeed
         * for the teleprompter auto-scroll feature. Default 60 px/sec.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN autoScrollSpeed INTEGER NOT NULL DEFAULT 60")
            }
        }

        /**
         * Migration v10 → v11: adds indexes on all non-PK filter columns.
         *
         * Before this, every chapters/bookmarks/highlights/reading_stats
         * lookup by novelId or chapterId was a full table scan — and the
         * chapters table carries the full text of downloaded chapters, so
         * those scans walked megabytes of prose.
         *
         * IMPORTANT: index names must exactly match what Room generates
         * from the entities' `indices = [...]` annotations, i.e.
         * `index_<table>_<column>` — otherwise Room's schema validation
         * throws IllegalStateException("Migration didn't properly handle")
         * on first open.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_novelId ON chapters(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_novelId ON bookmarks(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_chapterId ON bookmarks(chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_chapterId ON highlights(chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_novelId ON highlights(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_stats_novelId ON reading_stats(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_stats_completedAt ON reading_stats(completedAt)")
            }
        }

        /**
         * Migration v11 → v12: collections (categories) + updates feed.
         * Table/index definitions must match what Room generates from the
         * entity annotations exactly, or schema validation fails on open.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS novel_categories (
                        novelId TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        PRIMARY KEY(novelId, categoryId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_categories_categoryId ON novel_categories(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_categories_novelId ON novel_categories(novelId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS updates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        novelId TEXT NOT NULL,
                        novelTitle TEXT NOT NULL,
                        coverUrl TEXT,
                        newChapters INTEGER NOT NULL,
                        latestChapterNumber INTEGER NOT NULL,
                        foundAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_updates_novelId ON updates(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_updates_foundAt ON updates(foundAt)")
            }
        }

        /**
         * Migration v12 → v13: Reader Polish — text alignment/indent
         * settings and CUSTOM theme colors on reader_settings.
         * Color defaults are the Paper theme (0xFFF5F0E8 / 0xFF2D2A26)
         * expressed as signed 64-bit literals.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN justifyText INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN paragraphIndent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN customBackgroundColor INTEGER NOT NULL DEFAULT 4294308072")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN customTextColor INTEGER NOT NULL DEFAULT 4281149990")
            }
        }

        /**
         * Migration v13 → v14: full-text search index over downloaded
         * chapter content.
         *
         * The CREATE VIRTUAL TABLE statement must match what Room
         * generates from @Fts4(contentEntity = ChapterEntity::class)
         * — column list plus `content=` option — or schema validation
         * fails on first open. Room re-creates the external-content
         * sync triggers itself on every open, so the migration only
         * needs the table plus a one-time 'rebuild' to index chapters
         * that were downloaded before this version.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chapters_fts` " +
                            "USING FTS4(`content` TEXT, content=`chapters`)"
                )
                // Populate the index from existing rows. FTS4 'rebuild'
                // scans the content table once; NULL content rows
                // (not-downloaded chapters) index nothing.
                db.execSQL("INSERT INTO chapters_fts(chapters_fts) VALUES('rebuild')")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_reader_database"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
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