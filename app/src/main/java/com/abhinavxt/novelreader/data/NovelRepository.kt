package com.abhinavxt.novelreader.data

import com.abhinavxt.novelreader.data.database.AppDatabase
import androidx.room.withTransaction
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.database.ChapterEntity
import com.abhinavxt.novelreader.data.database.HighlightEntity
import com.abhinavxt.novelreader.data.database.NovelEntity
import com.abhinavxt.novelreader.data.database.ReadingProgressEntity
import com.abhinavxt.novelreader.data.database.ReaderSettingsEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.model.NovelPreview
import com.abhinavxt.novelreader.data.model.ReaderSettings
import com.abhinavxt.novelreader.data.model.ReaderTheme
import com.abhinavxt.novelreader.data.model.ReaderFont
import com.abhinavxt.novelreader.data.model.ReadingMode
import com.abhinavxt.novelreader.data.model.PageTransition
import com.abhinavxt.novelreader.data.source.Source
import com.abhinavxt.novelreader.data.source.SourceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.abhinavxt.novelreader.ui.screens.NovelDownloadInfo
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.flow.first

/**
 * [onProgressSaved] is an optional callback fired after every
 * saveReadingProgress(). The widget subsystem wires this up to refresh
 * the continue-reading widget cache. It's a plain lambda (not a DI thing)
 * to keep the repository free of Android/widget dependencies. Null in
 * tests; the Application sets a real callback at construction time.
 */
class NovelRepository(
    private val database: AppDatabase,
    private val onProgressSaved: (suspend (novelId: String) -> Unit)? = null
) {

    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val progressDao = database.readingProgressDao()
    private val settingsDao = database.readerSettingsDao()
    private val bookmarkDao = database.bookmarkDao()
    private val highlightDao = database.highlightDao()

    // ============ NETWORK OPERATIONS ============

    suspend fun searchNovels(query: String, source: Source = SourceManager.getDefaultSource()): List<NovelPreview> {
        return source.search(query)
    }

    suspend fun getPopularNovels(page: Int = 1, source: Source = SourceManager.getDefaultSource()): List<NovelPreview> {
        return source.getPopular(page)
    }

    suspend fun fetchNovelDetails(novelId: String, novelUrl: String): Novel? {
        val source = SourceManager.getSourceFromNovelId(novelId) ?: return null
        return source.getNovelDetails(novelUrl)
    }

    suspend fun fetchChapterContent(novelId: String, chapterUrl: String): String? {
        val source = SourceManager.getSourceFromNovelId(novelId) ?: return null
        return source.getChapterContent(chapterUrl)
    }

    // ============ LIBRARY OPERATIONS ============

    fun getLibraryNovels(): Flow<List<Novel>> {
        return novelDao.getAllNovels().map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }
    }

    suspend fun isInLibrary(novelId: String): Boolean {
        return novelDao.isNovelInLibrary(novelId)
    }

    fun isInLibraryFlow(novelId: String): Flow<Boolean> {
        return novelDao.isNovelInLibraryFlow(novelId)
    }

    suspend fun addToLibrary(novel: Novel) {
        database.withTransaction {
            val novelEntity = novel.toEntity()
            novelDao.insertNovel(novelEntity)

            val chapterEntities = novel.chapters.map { chapter ->
                ChapterEntity(
                    id = chapter.id,
                    novelId = novel.id,
                    number = chapter.number,
                    title = chapter.title,
                    url = chapter.url
                )
            }
            chapterDao.insertChapters(chapterEntities)
        }
    }

    suspend fun removeFromLibrary(novelId: String) {
        database.withTransaction {
            novelDao.deleteNovelById(novelId)
            chapterDao.deleteChaptersForNovel(novelId)
            progressDao.deleteProgress(novelId)
            bookmarkDao.deleteBookmarksForNovel(novelId)
            highlightDao.deleteHighlightsForNovel(novelId)  // Clean up highlights too
        }
        // Widget hook — if the removed novel was the widget's novel, the
        // callback will clear the widget. If not, it re-picks the most
        // recent progress. Either way, widget stays consistent.
        try {
            onProgressSaved?.invoke(novelId)
        } catch (e: Exception) {
            Logger.e("NovelRepository", "widget hook (remove) failed", e)
        }
    }

    suspend fun getNovelById(novelId: String): Novel? {
        val entity = novelDao.getNovelById(novelId) ?: return null
        return entity.toDomainModel()
    }

    // ============ CHAPTER OPERATIONS ============

    fun getChapters(novelId: String): Flow<List<Chapter>> {
        return chapterDao.getChaptersForNovel(novelId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * One-shot chapter fetch — used by the widget's rebuild path, which
     * runs in a short-lived coroutine and doesn't want to collect a flow.
     */
    suspend fun getChaptersOnce(novelId: String): List<Chapter> {
        return chapterDao.getChaptersForNovelOnce(novelId).map { it.toDomainModel() }
    }

    suspend fun downloadChapter(novelId: String, chapterId: String, chapterUrl: String): Boolean {
        return try {
            val content = fetchChapterContent(novelId, chapterUrl)
            if (content != null) {
                chapterDao.markChapterDownloaded(
                    chapterId = chapterId,
                    content = content,
                    downloadedAt = System.currentTimeMillis()
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.e("Error", e)
            false
        }
    }

    // ============ READING PROGRESS OPERATIONS ============

    fun getReadingProgressFlow(novelId: String): Flow<ReadingProgressEntity?> {
        return progressDao.getProgressFlow(novelId)
    }

    suspend fun saveReadingProgress(
        novelId: String,
        chapterId: String,
        chapterNumber: Int,
        paragraphIndex: Int = 0
    ) {
        val existing = progressDao.getProgress(novelId)

        val chaptersRead = if (existing != null) {
            if (existing.currentChapterId != chapterId) {
                existing.chaptersRead + 1
            } else {
                existing.chaptersRead
            }
        } else {
            1
        }

        progressDao.saveProgress(
            ReadingProgressEntity(
                novelId = novelId,
                currentChapterId = chapterId,
                currentChapterNumber = chapterNumber,
                paragraphIndex = paragraphIndex,
                chaptersRead = chaptersRead,
                lastReadAt = System.currentTimeMillis()
            )
        )

        // Widget hook — refresh the continue-reading widget cache.
        // Wrapped in try/catch so widget failures never break chapter
        // reading (the most critical user flow in the app).
        try {
            onProgressSaved?.invoke(novelId)
        } catch (e: Exception) {
            Logger.e("NovelRepository", "widget hook (save) failed", e)
        }
    }

    suspend fun getReadingProgress(novelId: String): ReadingProgressEntity? {
        return progressDao.getProgress(novelId)
    }

    suspend fun getMostRecentProgress(): ReadingProgressEntity? {
        return progressDao.getMostRecentProgress()
    }

    fun getAllReadingProgress(): Flow<List<ReadingProgressEntity>> {
        return progressDao.getAllProgress()
    }

    suspend fun getTotalChaptersRead(): Int {
        return progressDao.getTotalChaptersRead() ?: 0
    }

    fun getNovelsWithProgress(): Flow<List<ReadingProgressEntity>> {
        return progressDao.getAllProgress()
    }

    // ============ BOOKMARK OPERATIONS ============

    fun getBookmarksForNovel(novelId: String): Flow<List<BookmarkEntity>> {
        return bookmarkDao.getBookmarksForNovel(novelId)
    }

    fun getBookmarksForChapter(chapterId: String): Flow<List<BookmarkEntity>> {
        return bookmarkDao.getBookmarksForChapter(chapterId)
    }

    fun getBookmarkCount(novelId: String): Flow<Int> {
        return bookmarkDao.getBookmarkCount(novelId)
    }

    suspend fun isPositionBookmarked(chapterId: String, paragraphIndex: Int): Boolean {
        return bookmarkDao.isPositionBookmarked(chapterId, paragraphIndex)
    }

    suspend fun addBookmark(
        novelId: String,
        chapterId: String,
        chapterUrl: String,
        chapterNumber: Int,
        chapterTitle: String,
        paragraphIndex: Int,
        textSnippet: String,
        note: String? = null
    ): Long {
        val bookmark = BookmarkEntity(
            novelId = novelId,
            chapterId = chapterId,
            chapterUrl = chapterUrl,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            paragraphIndex = paragraphIndex,
            textSnippet = textSnippet,
            note = note
        )
        return bookmarkDao.insertBookmark(bookmark)
    }

    suspend fun updateBookmarkNote(bookmarkId: Long, note: String?) {
        bookmarkDao.updateNote(bookmarkId, note)
    }

    suspend fun deleteBookmark(bookmarkId: Long) {
        bookmarkDao.deleteBookmark(bookmarkId)
    }

    suspend fun deleteBookmarksForNovel(novelId: String) {
        bookmarkDao.deleteBookmarksForNovel(novelId)
    }

    suspend fun getAllBookmarksForBackup(): List<BookmarkEntity> {
        return bookmarkDao.getAllBookmarksOnce()
    }

    suspend fun insertBookmarkForRestore(bookmark: BookmarkEntity) {
        bookmarkDao.insertBookmarkReplace(bookmark)
    }

    // ============ HIGHLIGHT & ANNOTATION OPERATIONS ============

    /**
     * Get all highlights for a chapter. The reader observes this Flow
     * to render highlight overlays on top of paragraph text.
     */
    fun getHighlightsForChapter(chapterId: String): Flow<List<HighlightEntity>> {
        return highlightDao.getHighlightsForChapter(chapterId)
    }

    /**
     * Get all highlights for a novel — powers the "All Highlights" screen
     * accessible from the detail screen or a dedicated nav item.
     */
    fun getHighlightsForNovel(novelId: String): Flow<List<HighlightEntity>> {
        return highlightDao.getHighlightsForNovel(novelId)
    }

    /**
     * Get only highlights that have annotations — for a focused export view.
     */
    fun getAnnotationsForNovel(novelId: String): Flow<List<HighlightEntity>> {
        return highlightDao.getAnnotationsForNovel(novelId)
    }

    fun getHighlightCount(novelId: String): Flow<Int> {
        return highlightDao.getHighlightCount(novelId)
    }

    /**
     * Create a new highlight from a text selection in the reader.
     * Returns the auto-generated highlight ID.
     */
    suspend fun addHighlight(
        novelId: String,
        chapterId: String,
        chapterNumber: Int,
        chapterTitle: String,
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        selectedText: String,
        color: String = "YELLOW",
        note: String? = null
    ): Long {
        val highlight = HighlightEntity(
            novelId = novelId,
            chapterId = chapterId,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            paragraphIndex = paragraphIndex,
            startOffset = startOffset,
            endOffset = endOffset,
            selectedText = selectedText,
            color = color,
            note = note
        )
        return highlightDao.insertHighlight(highlight)
    }

    suspend fun updateHighlightNote(highlightId: Long, note: String?) {
        highlightDao.updateNote(highlightId, note)
    }

    suspend fun updateHighlightColor(highlightId: Long, color: String) {
        highlightDao.updateColor(highlightId, color)
    }

    suspend fun deleteHighlight(highlightId: Long) {
        highlightDao.deleteHighlight(highlightId)
    }

    // Backup/restore support for highlights
    suspend fun getAllHighlightsForBackup(): List<HighlightEntity> {
        return highlightDao.getAllHighlightsOnce()
    }

    suspend fun insertHighlightForRestore(highlight: HighlightEntity) {
        highlightDao.insertHighlightReplace(highlight)
    }

    // ============ UPDATE CHANGELOG OPERATIONS ============

    /**
     * Get the number of new chapters since the user last viewed the detail screen.
     * Returns 0 if the novel isn't in the library or there are no new chapters.
     */
    suspend fun getNewChapterCount(novelId: String): Int {
        val entity = novelDao.getNovelById(novelId) ?: return 0
        return (entity.totalChapters - entity.previousTotalChapters).coerceAtLeast(0)
    }

    /**
     * Mark the novel's update as "seen" — resets the badge by setting
     * previousTotalChapters = totalChapters.
     */
    suspend fun markUpdateSeen(novelId: String) {
        novelDao.markUpdateSeen(novelId)
    }

    // ============ READER SETTINGS OPERATIONS ============

    suspend fun getReaderSettings(): ReaderSettings {
        val entity = settingsDao.getSettings()
        return entity?.toDomainModel() ?: ReaderSettings()
    }

    fun getReaderSettingsFlow(): Flow<ReaderSettings> {
        return settingsDao.getSettingsFlow().map { entity ->
            entity?.toDomainModel() ?: ReaderSettings()
        }
    }

    suspend fun saveReaderSettings(settings: ReaderSettings) {
        val entity = ReaderSettingsEntity(
            id = 1,
            fontSize = settings.fontSize,
            theme = settings.theme.name,
            lineSpacing = settings.lineSpacing,
            font = settings.font.name,
            // ── New fields ──────────────────────────────────────
            readingMode = settings.readingMode.name,
            pageTransition = settings.pageTransition.name,
            horizontalMargin = settings.horizontalMargin,
            keepScreenOn = settings.keepScreenOn,
            volumeKeyNavigation = settings.volumeKeyNavigation,
            autoScrollSpeed = settings.autoScrollSpeed,  // NEW
        )
        settingsDao.saveSettings(entity)
    }

    // ============ EXTENSION FUNCTIONS FOR MAPPING ============

    private suspend fun NovelEntity.toDomainModel(): Novel {
        return Novel(
            id = this.id,
            title = this.title,
            author = this.author,
            coverUrl = this.coverUrl,
            description = this.description,
            source = this.source,
            status = this.status,
            chapters = emptyList()
        )
    }

    private fun Novel.toEntity(): NovelEntity {
        return NovelEntity(
            id = this.id,
            title = this.title,
            author = this.author,
            coverUrl = this.coverUrl,
            description = this.description,
            source = this.source,
            status = this.status,
            totalChapters = this.chapters.size
        )
    }

    private fun ChapterEntity.toDomainModel(): Chapter {
        return Chapter(
            id = this.id,
            number = this.number,
            title = this.title,
            url = this.url
        )
    }

    /**
     * Map the DB settings entity to the domain model.
     * Uses safe enum parsing with fallbacks so old/invalid values
     * don't crash the app — important after migrations.
     */
    private fun ReaderSettingsEntity.toDomainModel(): ReaderSettings {
        return ReaderSettings(
            fontSize = this.fontSize,
            theme = try {
                ReaderTheme.valueOf(this.theme)
            } catch (e: Exception) {
                ReaderTheme.PAPER
            },
            font = try {
                ReaderFont.valueOf(this.font)
            } catch (e: Exception) {
                ReaderFont.LITERATA
            },
            lineSpacing = this.lineSpacing,
            // ── New fields with safe parsing ────────────────────
            readingMode = try {
                ReadingMode.valueOf(this.readingMode)
            } catch (e: Exception) {
                ReadingMode.SCROLL
            },
            pageTransition = try {
                PageTransition.valueOf(this.pageTransition)
            } catch (e: Exception) {
                PageTransition.SLIDE
            },
            horizontalMargin = this.horizontalMargin.coerceIn(8, 40),
            keepScreenOn = this.keepScreenOn,
            volumeKeyNavigation = this.volumeKeyNavigation,
            autoScrollSpeed = this.autoScrollSpeed.coerceIn(20, 200),  // NEW
        )
    }

    suspend fun getMostRecentReadingProgress(): ReadingProgressEntity? {
        return progressDao.getMostRecentProgress()
    }

    suspend fun getChapterContent(novelId: String, chapterId: String, chapterUrl: String): String? {
        val downloadedChapter = chapterDao.getChapterById(chapterId)
        if (downloadedChapter?.isDownloaded == true && downloadedChapter.content != null) {
            return downloadedChapter.content
        }

        return fetchChapterContent(novelId, chapterUrl)
    }

    suspend fun isChapterDownloaded(chapterId: String): Boolean {
        return chapterDao.getChapterById(chapterId)?.isDownloaded == true
    }

    fun getDownloadedChapterCount(novelId: String): Flow<Int> {
        return chapterDao.getDownloadedChapterCount(novelId)
    }

    suspend fun getNovelsWithDownloads(): List<NovelDownloadInfo> {
        val novels = novelDao.getAllNovels().first()
        val result = mutableListOf<NovelDownloadInfo>()

        for (novelEntity in novels) {
            val chapters = chapterDao.getChaptersForNovelOnce(novelEntity.id)
            val downloadedChapters = chapters.filter { it.isDownloaded }

            if (downloadedChapters.isNotEmpty()) {
                val sizeBytes = downloadedChapters.sumOf {
                    (it.content?.length ?: 0).toLong() * 2
                }

                result.add(
                    NovelDownloadInfo(
                        novelId = novelEntity.id,
                        novelTitle = novelEntity.title,
                        coverUrl = novelEntity.coverUrl,
                        downloadedChapters = downloadedChapters.size,
                        totalChapters = chapters.size,
                        sizeBytes = sizeBytes
                    )
                )
            }
        }

        return result
    }

    // ============ Backup/Restore Methods ============

    suspend fun getAllNovelsForBackup(): List<NovelEntity> {
        return novelDao.getAllNovelsOnce()
    }

    suspend fun getAllChaptersForBackup(): List<ChapterEntity> {
        return novelDao.getAllChaptersOnce()
    }

    suspend fun getAllProgressForBackup(): List<ReadingProgressEntity> {
        return novelDao.getAllProgressOnce()
    }

    suspend fun insertNovelForRestore(novel: NovelEntity) {
        novelDao.insertNovel(novel)
    }

    suspend fun insertChapterForRestore(chapter: ChapterEntity) {
        novelDao.insertChapterReplace(chapter)
    }

    suspend fun insertProgressForRestore(progress: ReadingProgressEntity) {
        novelDao.insertProgressReplace(progress)
    }

    suspend fun restoreReaderSettings(fontSize: Int, theme: String, font: String) {
        val entity = ReaderSettingsEntity(
            id = 1,
            fontSize = fontSize,
            theme = theme,
            font = font
        )
        novelDao.insertSettingsReplace(entity)
    }
}