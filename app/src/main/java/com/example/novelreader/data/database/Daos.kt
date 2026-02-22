package com.example.novelreader.data.database

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
    suspend fun insertNovelReplace(novel: NovelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterReplace(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgressReplace(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettingsReplace(settings: ReaderSettingsEntity)
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
}

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE novelId = :novelId")
    suspend fun getProgress(novelId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE novelId = :novelId")
    suspend fun getProgressForNovel(novelId: String): ReadingProgressEntity?

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

    // Updated to use paragraphIndex instead of scrollPosition
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

    @Query("DELETE FROM reading_progress WHERE novelId = :novelId")
    suspend fun deleteProgressForNovel(novelId: String)
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

    // Get all bookmarks for a novel, newest first — used for the bookmark tab on NovelDetailScreen
    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY createdAt DESC")
    fun getBookmarksForNovel(novelId: String): Flow<List<BookmarkEntity>>

    // Get bookmarks for a specific chapter — useful in the reader to show if current chapter has bookmarks
    @Query("SELECT * FROM bookmarks WHERE chapterId = :chapterId ORDER BY paragraphIndex ASC")
    fun getBookmarksForChapter(chapterId: String): Flow<List<BookmarkEntity>>

    // Count bookmarks for a novel — handy for showing a badge count on the bookmark tab
    @Query("SELECT COUNT(*) FROM bookmarks WHERE novelId = :novelId")
    fun getBookmarkCount(novelId: String): Flow<Int>

    // Check if a specific position is already bookmarked — prevents duplicate bookmarks
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE chapterId = :chapterId AND paragraphIndex = :paragraphIndex)")
    suspend fun isPositionBookmarked(chapterId: String, paragraphIndex: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    // Update just the note field — for when users edit their annotation
    @Query("UPDATE bookmarks SET note = :note WHERE id = :bookmarkId")
    suspend fun updateNote(bookmarkId: Long, note: String?)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    // Clean up all bookmarks when a novel is removed from library
    @Query("DELETE FROM bookmarks WHERE novelId = :novelId")
    suspend fun deleteBookmarksForNovel(novelId: String)

    // For backup/restore support (matches your existing pattern)
    @Query("SELECT * FROM bookmarks")
    suspend fun getAllBookmarksOnce(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarkReplace(bookmark: BookmarkEntity)
}