package com.abhinavxt.novelforge.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {

    @Query("SELECT * FROM novels ORDER BY lastUpdatedAt DESC")
    fun getAllNovels(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :novelId")
    suspend fun getNovelById(novelId: String): NovelEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM novels WHERE id = :novelId)")
    suspend fun isNovelInLibrary(novelId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM novels WHERE id = :novelId)")
    fun isNovelInLibraryFlow(novelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNovel(novel: NovelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNovels(novels: List<NovelEntity>)

    @Update
    suspend fun updateNovel(novel: NovelEntity)

    @Delete
    suspend fun deleteNovel(novel: NovelEntity)

    @Query("DELETE FROM novels WHERE id = :novelId")
    suspend fun deleteNovelById(novelId: String)

    // ============ Backup/Restore Queries ============

    @Query("SELECT * FROM novels")
    suspend fun getAllNovelsOnce(): List<NovelEntity>

    @Query("SELECT * FROM chapters")
    suspend fun getAllChaptersOnce(): List<ChapterEntity>

    @Query("SELECT * FROM reading_progress")
    suspend fun getAllProgressOnce(): List<ReadingProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterReplace(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgressReplace(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettingsReplace(settings: ReaderSettingsEntity)

    // ── NEW: Mark novel as "seen" to clear update badge ─────────
    @Query("UPDATE novels SET previousTotalChapters = totalChapters WHERE id = :novelId")
    suspend fun markUpdateSeen(novelId: String)

    // ── Library-folder scan ─────────────────────────────────────

    /**
     * Every file URI already represented in the library. The scan subtracts
     * this from what it finds on disk, so a rescan of a 300-book folder
     * does one query instead of 300 existence checks.
     */
    @Query("SELECT sourceUri FROM novels WHERE sourceUri IS NOT NULL")
    suspend fun getImportedSourceUris(): List<String>

    @Query("SELECT sourceUri FROM novels WHERE id = :novelId")
    suspend fun getSourceUri(novelId: String): String?

    /**
     * Title of the book already holding this file's hash, or null if the
     * file is new. Returns the title rather than a boolean so the import
     * can name what it skipped instead of reporting a bare count.
     */
    @Query("SELECT title FROM novels WHERE contentHash = :hash LIMIT 1")
    suspend fun findTitleByContentHash(hash: String): String?

    @Query("SELECT * FROM novels WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): NovelEntity?

    /** Whether any book records [uri] as its file, so its grant is still needed. */
    @Query("SELECT EXISTS(SELECT 1 FROM novels WHERE sourceUri = :uri)")
    suspend fun isSourceUriInUse(uri: String): Boolean

    /**
     * Fallback duplicate check for books the hash cannot see: anything
     * imported before `contentHash` existed has none, so an exact-bytes
     * match will never fire for them.
     *
     * NOCASE on both columns because the same book imported from two
     * differently-cased files should still collide. Deliberately narrow —
     * title AND author, not title alone — so two unrelated books sharing a
     * common title are not merged.
     */
    @Query(
        "SELECT * FROM novels " +
                "WHERE title = :title COLLATE NOCASE " +
                "AND author = :author COLLATE NOCASE LIMIT 1"
    )
    suspend fun findByTitleAndAuthor(title: String, author: String): NovelEntity?

    /**
     * Attach a file's identity to a book that was already in the library
     * without one, so the legacy gap closes the first time the file is seen
     * rather than on every future import.
     *
     * COALESCE means this only ever fills a blank: a book that already has
     * a hash or a source URI keeps the one it has, so re-importing a
     * different edition can never steal the original's identity.
     */
    @Query(
        "UPDATE novels SET " +
                "contentHash = COALESCE(contentHash, :hash), " +
                "sourceUri = COALESCE(sourceUri, :uri) " +
                "WHERE id = :novelId"
    )
    suspend fun linkImportedFile(novelId: String, hash: String?, uri: String?)

    // ── Archive ─────────────────────────────────────────────────

    @Query(
        "UPDATE novels SET archivedAt = :archivedAt, textFingerprint = :fingerprint " +
                "WHERE id = :novelId"
    )
    suspend fun markArchived(novelId: String, archivedAt: Long, fingerprint: String)

    /**
     * Clear the archive mark after the text is back. [uri] replaces the
     * stored source only when given, which is the case when the user had to
     * point at the file again because the old one moved.
     */
    @Query(
        "UPDATE novels SET archivedAt = NULL, textFingerprint = :fingerprint, " +
                "totalChapters = :totalChapters, sourceUri = COALESCE(:uri, sourceUri) " +
                "WHERE id = :novelId"
    )
    suspend fun markRestored(
        novelId: String,
        fingerprint: String,
        totalChapters: Int,
        uri: String?
    )

    @Query("SELECT id FROM novels WHERE archivedAt IS NOT NULL")
    fun getArchivedNovelIds(): Flow<List<String>>

    /** Imported books still holding their text, in the order a bulk archive works through them. */
    @Query(
        "SELECT id FROM novels WHERE source = 'local' AND archivedAt IS NULL " +
                "ORDER BY title COLLATE NOCASE"
    )
    suspend fun getUnarchivedLocalNovelIds(): List<String>

    @Query("UPDATE novels SET readingStatus = :status WHERE id = :novelId")
    suspend fun setReadingStatus(novelId: String, status: String?)

    /** Starting a book moves it from nothing or Plan to read onto Reading; any other choice the reader made stays. */
    @Query("UPDATE novels SET readingStatus = 'READING' WHERE id = :novelId AND (readingStatus IS NULL OR readingStatus = 'PLAN_TO_READ')")
    suspend fun markReading(novelId: String)

    @Query("SELECT id, readingStatus FROM novels WHERE readingStatus IS NOT NULL")
    fun getReadingStatuses(): Flow<List<NovelStatusRow>>

    @Query("SELECT readerSettingsJson FROM novels WHERE id = :novelId")
    suspend fun getReaderSettingsJson(novelId: String): String?

    @Query("UPDATE novels SET readerSettingsJson = :json WHERE id = :novelId")
    suspend fun setReaderSettingsJson(novelId: String, json: String?)

    @Query("SELECT language FROM novels WHERE id = :novelId")
    suspend fun getLanguage(novelId: String): String?

    @Query("UPDATE novels SET language = :language WHERE id = :novelId")
    suspend fun setLanguage(novelId: String, language: String?)

    // Source migration: rows that only need their novelId re-pointed.
    @Query("UPDATE reading_stats SET novelId = :to WHERE novelId = :from")
    suspend fun moveReadingStats(from: String, to: String)

    @Query("UPDATE text_replacements SET novelId = :to WHERE novelId = :from")
    suspend fun moveTextReplacements(from: String, to: String)

    @Query("UPDATE novel_categories SET novelId = :to WHERE novelId = :from")
    suspend fun moveCategories(from: String, to: String)
}

data class NovelStatusRow(val id: String, val readingStatus: String)

/** A highlight with what it takes to show it outside its book and open the reader at it. */
data class HighlightWithBook(
    @androidx.room.Embedded val highlight: HighlightEntity,
    val novelTitle: String,
    val chapterUrl: String?
)

/**
 * Lightweight projection of ChapterEntity WITHOUT the content column.
 *
 * ChapterEntity.content holds the full chapter text for downloaded
 * chapters. A `SELECT *` list query therefore materializes every
 * downloaded chapter's prose in RAM just to render a title list —
 * 500 downloaded chapters ≈ 5–10 MB per Flow emission, and risks
 * SQLite's 2 MB CursorWindow limit. All list consumers only need
 * the fields below; content is fetched one chapter at a time via
 * getChapterById() when actually reading.
 */
data class ChapterListItem(
    val id: String,
    val novelId: String,
    val number: Int,
    val title: String,
    val url: String,
    val isDownloaded: Boolean,
    val downloadedAt: Long?
)

/**
 * Per-novel download aggregate, computed in SQL instead of loading
 * every chapter's content to sum sizes in Kotlin (see
 * NovelRepository.getNovelsWithDownloads).
 * sizeChars is SUM(LENGTH(content)) over downloaded chapters —
 * multiply by 2 for an approximate UTF-16 in-memory byte count.
 */
data class NovelDownloadAggregate(
    val novelId: String,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val sizeChars: Long
)

@Dao
interface ChapterDao {

    /** The stored text of chapters [from]..[to], for the recap; chapters without text are left out. */
    @Query("SELECT number, content FROM chapters WHERE novelId = :novelId AND number BETWEEN :from AND :to AND content IS NOT NULL ORDER BY number")
    suspend fun getStoredTexts(novelId: String, from: Int, to: Int): List<ChapterText>

    @Query("""
        SELECT id, novelId, number, title, url, isDownloaded, downloadedAt
        FROM chapters WHERE novelId = :novelId ORDER BY number ASC
    """)
    fun getChaptersForNovel(novelId: String): Flow<List<ChapterListItem>>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getChapterById(chapterId: String): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId")
    suspend fun getChapterCount(novelId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isDownloaded = 1, content = :content, downloadedAt = :downloadedAt WHERE id = :chapterId")
    suspend fun markChapterDownloaded(chapterId: String, content: String, downloadedAt: Long)

    @Query("DELETE FROM chapters WHERE novelId = :novelId")
    suspend fun deleteChaptersForNovel(novelId: String)

    @Query("DELETE FROM chapters WHERE novelId = :novelId AND number > :maxNumber")
    suspend fun deleteChaptersAfter(novelId: String, maxNumber: Int)

    @Query("UPDATE chapters SET isDownloaded = :isDownloaded, content = :content, downloadedAt = :downloadedAt WHERE id = :chapterId")
    suspend fun updateDownloadStatus(
        chapterId: String,
        isDownloaded: Boolean,
        content: String?,
        downloadedAt: Long?
    )

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND isDownloaded = 1")
    fun getDownloadedChapterCount(novelId: String): Flow<Int>

    @Query("UPDATE chapters SET isDownloaded = 0, content = NULL, downloadedAt = NULL WHERE novelId = :novelId")
    suspend fun deleteAllDownloadsForNovel(novelId: String)

    @Query("UPDATE chapters SET isDownloaded = 0, content = NULL, downloadedAt = NULL WHERE id = :chapterId")
    suspend fun deleteDownload(chapterId: String)

    @Query("""
        SELECT id, novelId, number, title, url, isDownloaded, downloadedAt
        FROM chapters WHERE novelId = :novelId ORDER BY number ASC
    """)
    suspend fun getChaptersForNovelOnce(novelId: String): List<ChapterListItem>

    // ── One-query replacement for the per-chapter isChapterDownloaded()
    //    loop in NovelDetailViewModel. Returns just the ids of downloaded
    //    chapters; the caller does set-membership checks in memory. ──
    @Query("SELECT id FROM chapters WHERE novelId = :novelId AND isDownloaded = 1")
    suspend fun getDownloadedChapterIds(novelId: String): List<String>

    // ── One-query replacement for the load-everything-and-sum-in-Kotlin
    //    approach in getNovelsWithDownloads(). LENGTH(content) runs inside
    //    SQLite; no chapter text ever crosses into the app process. ──
    @Query("""
        SELECT novelId,
               COUNT(*) AS totalChapters,
               SUM(CASE WHEN isDownloaded = 1 THEN 1 ELSE 0 END) AS downloadedChapters,
               COALESCE(SUM(CASE WHEN isDownloaded = 1 THEN LENGTH(content) ELSE 0 END), 0) AS sizeChars
        FROM chapters
        GROUP BY novelId
        HAVING downloadedChapters > 0
    """)
    suspend fun getDownloadAggregates(): List<NovelDownloadAggregate>

    @Delete
    suspend fun deleteChapter(chapter: ChapterEntity)

    // For update checker: get the highest chapter number stored for a novel
    @Query("SELECT MAX(number) FROM chapters WHERE novelId = :novelId")
    suspend fun getMaxChapterNumber(novelId: String): Int?

    // ── Full-text search over downloaded chapter content ────────
    // Joins the FTS index back to chapters (via the shared rowid)
    // and novels for display metadata. snippet() args: table,
    // match-start marker, match-end marker, ellipsis, column (-1 =
    // the matched one), approx tokens of context. \u0001/\u0002 are
    // control chars that can't appear in prose — the UI splits on
    // them to bold the match. Ordered by novel so the screen can
    // group results with simple adjacency, capped to keep a common
    // word from materializing thousands of rows.
    @Query("""
        SELECT c.id AS chapterId,
               c.novelId AS novelId,
               c.number AS chapterNumber,
               c.title AS chapterTitle,
               c.url AS chapterUrl,
               n.title AS novelTitle,
               n.coverUrl AS coverUrl,
               snippet(chapters_fts, '${'\u0001'}', '${'\u0002'}', '…', -1, 10) AS snippet
        FROM chapters_fts
        JOIN chapters c ON c.rowid = chapters_fts.rowid
        JOIN novels n ON n.id = c.novelId
        WHERE chapters_fts MATCH :ftsQuery
        ORDER BY n.title ASC, c.number ASC
        LIMIT 200
    """)
    suspend fun searchChapterContent(ftsQuery: String): List<ChapterSearchResult>
}

/**
 * One full-text search hit: enough metadata to render a grouped
 * result row (novel header + chapter line + snippet) and to
 * navigate straight into the reader.
 */
data class ChapterSearchResult(
    val chapterId: String,
    val novelId: String,
    val chapterNumber: Int,
    val chapterTitle: String,
    val chapterUrl: String,
    val novelTitle: String,
    val coverUrl: String?,
    val snippet: String
)

// ============ Character Codex (v16) ============

@Dao
interface CodexDao {

    // Display floor lives here, not in the scan: every candidate is
    // stored so partial counts survive across incremental scans, and
    // only names that have actually earned an entry are read back.
    @Query("""
        SELECT * FROM codex_names
        WHERE novelId = :novelId AND occurrences >= :minOccurrences
        ORDER BY occurrences DESC LIMIT 300
    """)
    fun getNamesFlow(novelId: String, minOccurrences: Int): Flow<List<CodexNameEntity>>

    @Query("SELECT * FROM codex_names WHERE novelId = :novelId")
    suspend fun getNamesOnce(novelId: String): List<CodexNameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNames(names: List<CodexNameEntity>)

    @Query("DELETE FROM codex_names WHERE novelId = :novelId")
    suspend fun clearNames(novelId: String)

    @Query("DELETE FROM codex_names WHERE novelId = :novelId AND name IN (:names)")
    suspend fun deleteNames(novelId: String, names: List<String>)

    @Query("SELECT * FROM codex_scan_info WHERE novelId = :novelId")
    suspend fun getScanInfo(novelId: String): CodexScanInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScanInfo(info: CodexScanInfoEntity)

    @Query("DELETE FROM codex_scan_info WHERE novelId = :novelId")
    suspend fun clearScanInfo(novelId: String)

    // ── Mentions: live FTS lookup, spoiler-capped ────────────────
    // Same index the library search uses; scoped to one novel and
    // capped at the reader's current chapter so the codex can't
    // spoil. Ordered by chapter for the timeline + sparkline.
    @Query("""
        SELECT c.id AS chapterId,
               c.number AS chapterNumber,
               c.title AS chapterTitle,
               c.url AS chapterUrl,
               snippet(chapters_fts, '${'\u0001'}', '${'\u0002'}', '…', -1, 12) AS snippet
        FROM chapters_fts
        JOIN chapters c ON c.rowid = chapters_fts.rowid
        WHERE chapters_fts MATCH :ftsQuery
          AND c.novelId = :novelId
          AND c.number <= :maxChapterNumber
        ORDER BY c.number ASC
        LIMIT 400
    """)
    suspend fun getMentions(
        novelId: String,
        ftsQuery: String,
        maxChapterNumber: Int
    ): List<CodexMention>

    // Lightweight variant for the relationship graph: co-occurrence
    // only needs WHICH chapters a name appears in, not snippets —
    // skipping snippet() keeps ~25 parallel queries cheap.
    @Query("""
        SELECT c.number
        FROM chapters_fts
        JOIN chapters c ON c.rowid = chapters_fts.rowid
        WHERE chapters_fts MATCH :ftsQuery
          AND c.novelId = :novelId
          AND c.number <= :maxChapterNumber
    """)
    suspend fun getMentionChapterNumbers(
        novelId: String,
        ftsQuery: String,
        maxChapterNumber: Int
    ): List<Int>
}

/** One chapter where a codex name appears, with an FTS snippet. */
data class CodexMention(
    val chapterId: String,
    val chapterNumber: Int,
    val chapterTitle: String,
    val chapterUrl: String,
    val snippet: String
)

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE novelId = :novelId")
    suspend fun getProgress(novelId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE novelId = :novelId")
    fun getProgressFlow(novelId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress ORDER BY lastReadAt DESC")
    fun getAllProgress(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress ORDER BY lastReadAt DESC LIMIT 1")
    suspend fun getMostRecentProgress(): ReadingProgressEntity?

    @Query("SELECT SUM(chaptersRead) FROM reading_progress")
    suspend fun getTotalChaptersRead(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: ReadingProgressEntity)

    @Query("""
        UPDATE reading_progress 
        SET currentChapterId = :chapterId, 
            currentChapterNumber = :chapterNumber,
            paragraphIndex = :paragraphIndex,
            lastReadAt = :timestamp 
        WHERE novelId = :novelId
    """)
    suspend fun updateProgress(
        novelId: String,
        chapterId: String,
        chapterNumber: Int = 0,
        paragraphIndex: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE reading_progress SET chaptersRead = chaptersRead + 1, lastReadAt = :timestamp WHERE novelId = :novelId")
    suspend fun incrementChaptersRead(novelId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM reading_progress WHERE novelId = :novelId")
    suspend fun deleteProgress(novelId: String)
}

@Dao
interface ReaderSettingsDao {

    @Query("SELECT * FROM reader_settings WHERE id = 1")
    suspend fun getSettings(): ReaderSettingsEntity?

    @Query("SELECT * FROM reader_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<ReaderSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: ReaderSettingsEntity)

    @Query("UPDATE reader_settings SET fontSize = :fontSize WHERE id = 1")
    suspend fun updateFontSize(fontSize: Int)

    @Query("UPDATE reader_settings SET theme = :theme WHERE id = 1")
    suspend fun updateTheme(theme: String)
}

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY createdAt DESC")
    fun getBookmarksForNovel(novelId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE chapterId = :chapterId ORDER BY paragraphIndex ASC")
    fun getBookmarksForChapter(chapterId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE novelId = :novelId")
    fun getBookmarkCount(novelId: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE chapterId = :chapterId AND paragraphIndex = :paragraphIndex)")
    suspend fun isPositionBookmarked(chapterId: String, paragraphIndex: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET note = :note WHERE id = :bookmarkId")
    suspend fun updateNote(bookmarkId: Long, note: String?)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("DELETE FROM bookmarks WHERE novelId = :novelId")
    suspend fun deleteBookmarksForNovel(novelId: String)

    @Query("SELECT * FROM bookmarks")
    suspend fun getAllBookmarksOnce(): List<BookmarkEntity>

    // Phase 7: annotation export
    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY chapterNumber, paragraphIndex")
    suspend fun getBookmarksForNovelOnce(novelId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarkReplace(bookmark: BookmarkEntity)
}

// ============ Pronunciation Dictionary DAO ============

@Dao
interface PronunciationDao {

    @Query("SELECT * FROM pronunciation_entries ORDER BY word ASC")
    fun getAllEntries(): Flow<List<PronunciationEntry>>

    @Query("SELECT * FROM pronunciation_entries ORDER BY word ASC")
    suspend fun getAllEntriesOnce(): List<PronunciationEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PronunciationEntry): Long

    @Query("UPDATE pronunciation_entries SET word = :word, replacement = :replacement WHERE id = :id")
    suspend fun updateEntry(id: Long, word: String, replacement: String)

    @Query("DELETE FROM pronunciation_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM pronunciation_entries")
    suspend fun deleteAll()
}

// ============ Reading Stats DAO ============

@Dao
interface ReadingStatDao {

    @Insert
    suspend fun insertEvent(event: ReadingStatEvent)

    // Total words read all time
    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_stats")
    suspend fun getTotalWordsRead(): Int

    // Total reading time all time (ms)
    @Query("SELECT COALESCE(SUM(readingTimeMs), 0) FROM reading_stats")
    suspend fun getTotalReadingTimeMs(): Long

    // Total chapters completed
    @Query("SELECT COUNT(*) FROM reading_stats")
    suspend fun getTotalChaptersCompleted(): Int

    // Stats for a given day (midnight to midnight)
    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_stats WHERE completedAt >= :dayStartMs AND completedAt < :dayEndMs")
    suspend fun getWordsReadForDay(dayStartMs: Long, dayEndMs: Long): Int

    @Query("SELECT COALESCE(SUM(readingTimeMs), 0) FROM reading_stats WHERE completedAt >= :dayStartMs AND completedAt < :dayEndMs")
    suspend fun getReadingTimeMsForDay(dayStartMs: Long, dayEndMs: Long): Long

    @Query("SELECT COUNT(*) FROM reading_stats WHERE completedAt >= :dayStartMs AND completedAt < :dayEndMs")
    suspend fun getChaptersCompletedForDay(dayStartMs: Long, dayEndMs: Long): Int

    // Days with any reading activity — return raw timestamps for local timezone bucketing
    @Query("SELECT completedAt FROM reading_stats ORDER BY completedAt DESC")
    suspend fun getAllCompletedTimestamps(): List<Long>

    // Phase 7: reading history — events joined with chapter titles in
    // one query (LEFT JOIN survives deleted chapters).
    @Query("""
        SELECT rs.novelId AS novelId,
               rs.chapterId AS chapterId,
               c.title AS chapterTitle,
               c.number AS chapterNumber,
               rs.wordsRead AS wordsRead,
               rs.readingTimeMs AS readingTimeMs,
               rs.completedAt AS completedAt
        FROM reading_stats rs
        LEFT JOIN chapters c ON c.id = rs.chapterId
        ORDER BY rs.completedAt DESC
        LIMIT :limit
    """)
    suspend fun getHistoryRows(limit: Int): List<HistoryRowData>

    // Per-novel stats
    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_stats WHERE novelId = :novelId")
    suspend fun getWordsReadForNovel(novelId: String): Int

    @Query("SELECT COALESCE(SUM(readingTimeMs), 0) FROM reading_stats WHERE novelId = :novelId")
    suspend fun getReadingTimeMsForNovel(novelId: String): Long

    @Query("SELECT COUNT(*) FROM reading_stats WHERE novelId = :novelId")
    suspend fun getChaptersCompletedForNovel(novelId: String): Int

    // Recent activity (last 30 days for chart)
    @Query("SELECT * FROM reading_stats WHERE completedAt >= :sinceMs ORDER BY completedAt ASC")
    suspend fun getEventsSince(sinceMs: Long): List<ReadingStatEvent>

    // Recent sessions — latest N sessions for the activity feed
    @Query("SELECT * FROM reading_stats ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 20): List<ReadingStatEvent>

    // Longest single reading session in ms
    @Query("SELECT COALESCE(MAX(readingTimeMs), 0) FROM reading_stats")
    suspend fun getLongestSessionMs(): Long

    // Most words read in a single session
    @Query("SELECT COALESCE(MAX(wordsRead), 0) FROM reading_stats")
    suspend fun getMostWordsInSession(): Int

    // Total distinct novels read
    @Query("SELECT COUNT(DISTINCT novelId) FROM reading_stats")
    suspend fun getDistinctNovelsRead(): Int

    // Daily reading time in ms for a given day range — already defined above as getReadingTimeMsForDay
    // For backup
    @Query("SELECT * FROM reading_stats")
    suspend fun getAllEventsOnce(): List<ReadingStatEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventReplace(event: ReadingStatEvent)
}

// ============ Highlights & Annotations DAO ============

@Dao
interface HighlightDao {

    // Get all highlights for a chapter — used by reader to render overlays
    @Query("SELECT * FROM highlights WHERE chapterId = :chapterId ORDER BY paragraphIndex ASC, startOffset ASC")
    fun getHighlightsForChapter(chapterId: String): Flow<List<HighlightEntity>>

    // Get all highlights for a novel — used by "All Highlights" screen
    @Query("SELECT * FROM highlights WHERE novelId = :novelId ORDER BY chapterNumber ASC, paragraphIndex ASC")
    fun getHighlightsForNovel(novelId: String): Flow<List<HighlightEntity>>

    // Get highlights with notes only (annotations) — useful for export
    @Query("SELECT * FROM highlights WHERE novelId = :novelId AND note IS NOT NULL AND note != '' ORDER BY chapterNumber ASC, paragraphIndex ASC")
    fun getAnnotationsForNovel(novelId: String): Flow<List<HighlightEntity>>

    // Total highlight count for a novel
    @Query("SELECT COUNT(*) FROM highlights WHERE novelId = :novelId")
    fun getHighlightCount(novelId: String): Flow<Int>

    // Insert a new highlight
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    // Update the note (annotation) on an existing highlight
    @Query("UPDATE highlights SET note = :note, updatedAt = :updatedAt WHERE id = :highlightId")
    suspend fun updateNote(highlightId: Long, note: String?, updatedAt: Long = System.currentTimeMillis())

    // Change highlight color
    @Query("UPDATE highlights SET color = :color, updatedAt = :updatedAt WHERE id = :highlightId")
    suspend fun updateColor(highlightId: Long, color: String, updatedAt: Long = System.currentTimeMillis())

    // Delete a single highlight
    @Query("DELETE FROM highlights WHERE id = :highlightId")
    suspend fun deleteHighlight(highlightId: Long)

    // Delete all highlights for a novel (when removing from library)
    @Query("DELETE FROM highlights WHERE novelId = :novelId")
    suspend fun deleteHighlightsForNovel(novelId: String)

    // Backup/restore
    @Query("SELECT * FROM highlights")
    suspend fun getAllHighlightsOnce(): List<HighlightEntity>

    // Phase 7: annotation export
    @Query("SELECT * FROM highlights WHERE novelId = :novelId ORDER BY chapterNumber, paragraphIndex, startOffset")
    suspend fun getHighlightsForNovelOnce(novelId: String): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlightReplace(highlight: HighlightEntity)

    // Every highlight across the library, newest first, for the highlights screen and the daily card.
    @Query("""
        SELECT h.*, n.title AS novelTitle, c.url AS chapterUrl FROM highlights h
        JOIN novels n ON n.id = h.novelId
        LEFT JOIN chapters c ON c.id = h.chapterId
        ORDER BY h.createdAt DESC
    """)
    fun getAllHighlightsWithBook(): Flow<List<HighlightWithBook>>
}
// ─────────────────────────────────────────────────────────────────
// Phase 6: collections + updates feed
// ─────────────────────────────────────────────────────────────────

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query("DELETE FROM novel_categories WHERE categoryId = :categoryId")
    suspend fun clearCategoryAssignments(categoryId: Long)

    // All novel↔category links as one observable list — the ViewModel
    // folds it into a Map<novelId, Set<categoryId>> for filtering.
    @Query("SELECT * FROM novel_categories")
    fun getAllCrossRefs(): Flow<List<NovelCategoryCrossRef>>

    @Query("SELECT categoryId FROM novel_categories WHERE novelId = :novelId")
    suspend fun getCategoryIdsForNovel(novelId: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<NovelCategoryCrossRef>)

    @Query("DELETE FROM novel_categories WHERE novelId = :novelId")
    suspend fun clearNovelCategories(novelId: String)
}

@Dao
interface UpdateDao {

    @Query("SELECT * FROM updates ORDER BY foundAt DESC LIMIT 200")
    fun getRecentUpdates(): Flow<List<UpdateEntity>>

    @Insert
    suspend fun insertUpdate(update: UpdateEntity)

    @Query("DELETE FROM updates")
    suspend fun clearAll()

    @Query("DELETE FROM updates WHERE novelId = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("DELETE FROM updates WHERE foundAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

// ─────────────────────────────────────────────────────────────────
// Phase 7: reading history + annotation export
// ─────────────────────────────────────────────────────────────────

/**
 * One reading-history row: a stats event joined with its chapter's
 * title/number. LEFT JOIN because the chapter may have been deleted
 * (novel removed and re-added, etc.) — title/number are then null.
 */
data class HistoryRowData(
    val novelId: String,
    val chapterId: String,
    val chapterTitle: String?,
    val chapterNumber: Int?,
    val wordsRead: Int,
    val readingTimeMs: Long,
    val completedAt: Long
)
/** Formatting and contents of imported books (v21). */
@Dao
interface BookFormattingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormatting(rows: List<ChapterFormattingEntity>)

    @Query("SELECT json FROM chapter_formatting WHERE chapterId = :chapterId")
    suspend fun getFormattingJson(chapterId: String): String?

    @Query("DELETE FROM chapter_formatting WHERE novelId = :novelId")
    suspend fun deleteFormattingForNovel(novelId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToc(rows: List<TocEntryEntity>)

    @Query("SELECT * FROM book_toc WHERE novelId = :novelId ORDER BY position")
    suspend fun getToc(novelId: String): List<TocEntryEntity>

    @Query("DELETE FROM book_toc WHERE novelId = :novelId")
    suspend fun deleteTocForNovel(novelId: String)
}

// ============ Text replacement DAO ============

@Dao
interface TextReplacementDao {

    @Query("SELECT * FROM text_replacements ORDER BY find COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TextReplacementEntity>>

    @Query("SELECT * FROM text_replacements ORDER BY find COLLATE NOCASE ASC")
    suspend fun getAll(): List<TextReplacementEntity>

    @Insert
    suspend fun insert(rule: TextReplacementEntity): Long

    @Query("UPDATE text_replacements SET novelId = :novelId, find = :find, `replace` = :replace WHERE id = :id")
    suspend fun update(id: Long, novelId: String?, find: String, replace: String)

    @Query("DELETE FROM text_replacements WHERE id = :id")
    suspend fun delete(id: Long)
}

// ============ Vocabulary DAO ============

@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    suspend fun getAll(): List<VocabularyEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary WHERE `key` = :key)")
    suspend fun has(key: String): Boolean

    /** Saving a word twice keeps the first card and its progress. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: VocabularyEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(words: List<VocabularyEntity>)

    @Query("UPDATE vocabulary SET box = :box, dueAt = :dueAt WHERE id = :id")
    suspend fun schedule(id: Long, box: Int, dueAt: Long)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vocabulary WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

/** A chapter's number and stored text. */
data class ChapterText(val number: Int, val content: String)

// ============ Translation DAO ============

@Dao
interface TranslationDao {

    @Query("SELECT translate FROM novels WHERE id = :novelId")
    suspend fun isTranslateOn(novelId: String): Boolean?

    @Query("SELECT translate FROM novels WHERE id = :novelId")
    fun observeTranslateOn(novelId: String): Flow<Boolean?>

    @Query("SELECT language FROM novels WHERE id = :novelId")
    suspend fun getLanguage(novelId: String): String?

    @Query("UPDATE novels SET translate = :on WHERE id = :novelId")
    suspend fun setTranslate(novelId: String, on: Boolean)

    @Query("SELECT * FROM chapter_originals WHERE chapterId = :chapterId")
    suspend fun getOriginal(chapterId: String): ChapterOriginalEntity?

    @Query("SELECT * FROM chapter_originals WHERE novelId = :novelId")
    suspend fun getOriginals(novelId: String): List<ChapterOriginalEntity>

    @Query("SELECT * FROM chapter_originals")
    suspend fun getAllOriginals(): List<ChapterOriginalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOriginal(original: ChapterOriginalEntity)

    @Query("DELETE FROM chapter_originals WHERE chapterId = :chapterId")
    suspend fun deleteOriginal(chapterId: String)

    @Query("DELETE FROM chapter_originals WHERE novelId = :novelId")
    suspend fun deleteOriginals(novelId: String)

    /** Chapters whose stored text is in place, translated or not; the caller sorts out which are. */
    @Query("SELECT id, content FROM chapters WHERE novelId = :novelId AND content IS NOT NULL ORDER BY number")
    suspend fun getStoredChapterIds(novelId: String): List<StoredChapterRef>

    @Query("SELECT content FROM chapters WHERE id = :chapterId")
    suspend fun getContent(chapterId: String): String?

    /** Stores a chapter's text; one read online becomes downloaded, so its translation is kept. */
    @Query("UPDATE chapters SET content = :content, isDownloaded = 1, downloadedAt = COALESCE(downloadedAt, :now) WHERE id = :chapterId")
    suspend fun setContent(chapterId: String, content: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM glossary_terms WHERE novelId = :novelId ORDER BY source COLLATE NOCASE")
    fun observeGlossary(novelId: String): Flow<List<GlossaryTermEntity>>

    @Query("SELECT * FROM glossary_terms WHERE novelId = :novelId")
    suspend fun getGlossary(novelId: String): List<GlossaryTermEntity>

    @Query("SELECT * FROM glossary_terms")
    suspend fun getAllGlossary(): List<GlossaryTermEntity>

    /** New terms the translator found; one the reader already has is kept as they set it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTerms(terms: List<GlossaryTermEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTerm(term: GlossaryTermEntity): Long

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun deleteTerm(id: Long)

    @Query("DELETE FROM glossary_terms WHERE novelId = :novelId")
    suspend fun deleteGlossary(novelId: String)
}

/** A stored chapter's id and text, to tell translated chapters from the rest. */
data class StoredChapterRef(val id: String, val content: String)
