package com.abhinavxt.novelforge.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema versions are deliberately disjoint between the two builds.
 * Both share history up to 16, then the local-only build jumps to the
 * 100 range and the sources build continues from 17. They diverged for
 * real when local-only dropped `updates` and `novels.previousTotalChapters`,
 * so the same number would mean two different schemas — and a database
 * carried across builds would be handed a migration written for the
 * other one's shape. The gap makes that collision impossible rather
 * than merely unlikely.
 */
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
        ChapterFtsEntity::class,         // NEW in v14 — full-text search index
        CodexNameEntity::class,          // NEW in v16 — character codex
        CodexScanInfoEntity::class,      // NEW in v16 — codex scan watermark
        ChapterFormattingEntity::class,  // NEW in v21 — EPUB formatting beside the text
        TocEntryEntity::class,           // NEW in v21 — EPUB table of contents
        TextReplacementEntity::class     // NEW in v22 — text replacement while reading
    ],
    version = 25,
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
    abstract fun codexDao(): CodexDao
    abstract fun highlightDao(): HighlightDao            // NEW in v9
    abstract fun categoryDao(): CategoryDao              // NEW in v12
    abstract fun updateDao(): UpdateDao                  // NEW in v12
    abstract fun bookFormattingDao(): BookFormattingDao  // NEW in v21
    abstract fun textReplacementDao(): TextReplacementDao // NEW in v22

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

        /**
         * Migration v14 → v15: tap-zone layout setting on the
         * reader_settings singleton. Default 'SIDES' preserves the
         * previous hardcoded behavior for existing users.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reader_settings " +
                            "ADD COLUMN tapZoneLayout TEXT NOT NULL DEFAULT 'SIDES'"
                )
            }
        }

        /**
         * Migration v15 → v16: character codex tables. codex_names is
         * the aggregate produced by the heuristic scan; codex_scan_info
         * is the per-novel incremental-scan watermark. Index name must
         * match Room's generated convention.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `codex_names` (" +
                            "`novelId` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`occurrences` INTEGER NOT NULL, " +
                            "`chapterCount` INTEGER NOT NULL, " +
                            "`firstChapterNumber` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`novelId`, `name`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_codex_names_novelId` " +
                            "ON `codex_names` (`novelId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `codex_scan_info` (" +
                            "`novelId` TEXT NOT NULL, " +
                            "`lastScannedNumber` INTEGER NOT NULL, " +
                            "`scannedChapters` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`novelId`))"
                )
            }
        }

        /**
         * Migration v16 → v17: codex entries gain a range end and
         * accumulated speech evidence. Two plain ADD COLUMNs — no
         * table rebuild, since codex_names has no dropped columns and
         * its index is on novelId, which isn't touched.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `codex_names` " +
                            "ADD COLUMN `lastChapterNumber` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `codex_names` " +
                            "ADD COLUMN `speechHits` INTEGER NOT NULL DEFAULT 0"
                )
                // Backfill the range end from what we already know, so
                // existing entries read "Ch. 12" rather than "Ch. 12-0"
                // before their first rescan.
                db.execSQL("UPDATE `codex_names` SET `lastChapterNumber` = `firstChapterNumber`")
            }
        }

        /**
         * Migration v17 → v18: `novels.sourceUri` records the file a book
         * was imported from, so a library-folder scan can tell what it has
         * already seen. Nullable with no default — existing rows become
         * null, which is correct: nothing before this point was scanned,
         * and a null simply means "not from a file I am tracking".
         *
         * Books imported by hand before v18 therefore look new to the
         * first scan. Deduping those would need to compare titles, which
         * guesses; letting the user delete the odd duplicate does not.
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `sourceUri` TEXT")
            }
        }

        /**
         * Migration v18 → v19: `novels.contentHash` holds the SHA-256 of an
         * imported file, so the same file cannot enter the library twice.
         *
         * Nullable and unbackfilled, necessarily: the hash can only be
         * computed by reading the file, and for books imported before v19
         * that file may have been moved or deleted. Duplicate detection
         * therefore only covers books imported from this version onward,
         * and the gap closes on its own as the old ones are re-imported.
         *
         * No index. This is looked up once per imported file against a
         * table holding hundreds of rows, not millions, where the scan
         * costs less than maintaining the index would.
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `contentHash` TEXT")
            }
        }

        /**
         * Migration v19 → v20: archiving local books.
         *
         * `archivedAt` marks a book whose chapter text was dropped and can be
         * re-read from its file; `textFingerprint` lets that re-read prove it
         * produced the same chapters. Both nullable, no backfill: existing
         * books are not archived, and their fingerprint is computed the first
         * time they are archived, from the text still in the database.
         */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `archivedAt` INTEGER")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `textFingerprint` TEXT")
            }
        }

        /**
         * v21: imported books keep their formatting (italics, images,
         * footnotes) and their own table of contents, in tables of their
         * own so chapter text stays plain. Nothing to backfill: books already
         * in the library pick it up the next time they are restored or
         * imported.
         */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_formatting` (`chapterId` TEXT NOT NULL, " +
                        "`novelId` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`chapterId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_formatting_novelId` ON `chapter_formatting` (`novelId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_toc` (`novelId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, `depth` INTEGER NOT NULL, `chapterNumber` INTEGER NOT NULL, " +
                        "`paragraph` INTEGER NOT NULL, PRIMARY KEY(`novelId`, `position`))"
                )
            }
        }

        /**
         * v22: rules the reader applies to what it shows, for one book or
         * all of them. The chapter text itself is never rewritten.
         */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `text_replacements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`novelId` TEXT, `find` TEXT NOT NULL, `replace` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_text_replacements_novelId` ON `text_replacements` (`novelId`)"
                )
            }
        }

        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN customFont TEXT")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN hyphenation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN paragraphSpacing INTEGER NOT NULL DEFAULT 16")
            }
        }

        /** v24: reading status and per-book reader settings, both on the novel row so backups and migration carry them. */
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE novels ADD COLUMN readingStatus TEXT")
                db.execSQL("ALTER TABLE novels ADD COLUMN readerSettingsJson TEXT")
            }
        }

        /**
         * v25: the book's language, so hyphenation follows the book rather than the device.
         * Also repairs bookmarks that 2.3.0's source migration left on chapters the new source lacks.
         */
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE novels ADD COLUMN language TEXT")
                reattachStrandedBookmarks(db)
            }
        }

        /**
         * Points each bookmark whose chapter is gone at the book's nearest chapter (same rule as
         * closestChapter: the last one at or before its number, else the first), at its top, since
         * the paragraph belonged to other text. Books with no chapter rows are left alone.
         * SQLite evaluates every SET against the row as it was, so the subqueries see the old number.
         */
        internal fun reattachStrandedBookmarks(db: SupportSQLiteDatabase) {
            val nearest = { column: String ->
                "COALESCE(" +
                    "(SELECT c.$column FROM chapters c WHERE c.novelId = bookmarks.novelId " +
                    "AND c.number <= bookmarks.chapterNumber ORDER BY c.number DESC LIMIT 1), " +
                    "(SELECT c.$column FROM chapters c WHERE c.novelId = bookmarks.novelId ORDER BY c.number LIMIT 1))"
            }
            db.execSQL(
                "UPDATE bookmarks SET chapterId = ${nearest("id")}, chapterUrl = ${nearest("url")}, " +
                    "chapterNumber = ${nearest("number")}, chapterTitle = ${nearest("title")}, paragraphIndex = 0 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM chapters c WHERE c.id = bookmarks.chapterId AND c.novelId = bookmarks.novelId) " +
                    "AND EXISTS (SELECT 1 FROM chapters c WHERE c.novelId = bookmarks.novelId)"
            )
        }

        /** The file name under the app's databases directory. */
        const val DATABASE_NAME = "novel_reader_database"

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
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