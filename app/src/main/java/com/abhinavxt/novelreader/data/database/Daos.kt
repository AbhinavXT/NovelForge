package com.abhinavxt.novelreader.data.database

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
}

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY number ASC")
    fun getChaptersForNovel(novelId: String): Flow<List<ChapterEntity>>

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

    @Query("UPDATE chapters SET isDownloaded = :isDownloaded, content = :content, downloadedAt = :downloadedAt WHERE id = :chapterId")
    suspend fun updateDownloadStatus(
        chapterId: String,
        isDownloaded: Boolean,
        content: String?,
        downloadedAt: Long?
    )

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND isDownloaded = 1 ORDER BY number ASC")
    fun getDownloadedChapters(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND isDownloaded = 1")
    fun getDownloadedChapterCount(novelId: String): Flow<Int>

    @Query("UPDATE chapters SET isDownloaded = 0, content = NULL, downloadedAt = NULL WHERE novelId = :novelId")
    suspend fun deleteAllDownloadsForNovel(novelId: String)

    @Query("UPDATE chapters SET isDownloaded = 0, content = NULL, downloadedAt = NULL WHERE id = :chapterId")
    suspend fun deleteDownload(chapterId: String)

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY number ASC")
    suspend fun getChaptersForNovelOnce(novelId: String): List<ChapterEntity>

    @Delete
    suspend fun deleteChapter(chapter: ChapterEntity)

    // For update checker: get the highest chapter number stored for a novel
    @Query("SELECT MAX(number) FROM chapters WHERE novelId = :novelId")
    suspend fun getMaxChapterNumber(novelId: String): Int?
}

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlightReplace(highlight: HighlightEntity)
}