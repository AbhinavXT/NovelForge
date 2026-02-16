package com.example.novelreader.data

import com.example.novelreader.data.database.AppDatabase
import com.example.novelreader.data.database.ChapterEntity
import com.example.novelreader.data.database.NovelEntity
import com.example.novelreader.data.database.ReadingProgressEntity
import com.example.novelreader.data.database.ReaderSettingsEntity
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.data.model.NovelPreview
import com.example.novelreader.data.model.ReaderSettings
import com.example.novelreader.data.model.ReaderTheme
import com.example.novelreader.data.model.ReaderFont
import com.example.novelreader.data.source.Source
import com.example.novelreader.data.source.SourceManager
import com.example.novelreader.data.model.ChapterNavInfo
import com.example.novelreader.data.model.ReaderChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.novelreader.ui.screens.NovelDownloadInfo
import kotlinx.coroutines.flow.first

class NovelRepository(private val database: AppDatabase) {

    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val progressDao = database.readingProgressDao()
    private val settingsDao = database.readerSettingsDao()

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

    suspend fun removeFromLibrary(novelId: String) {
        novelDao.deleteNovelById(novelId)
        chapterDao.deleteChaptersForNovel(novelId)
        progressDao.deleteProgress(novelId)
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
            e.printStackTrace()
            false
        }
    }

    // ============ READING PROGRESS OPERATIONS ============

    fun getReadingProgressFlow(novelId: String): Flow<ReadingProgressEntity?> {
        return progressDao.getProgressFlow(novelId)
    }

    // Updated to use paragraphIndex instead of scrollPosition
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

    suspend fun getReaderChapter(
        novelId: String,
        chapterId: String,
        chapterUrl: String
    ): ReaderChapter? {
        val content = fetchChapterContent(novelId, chapterUrl) ?: return null

        val novel = getNovelById(novelId)

        var chapters: List<Chapter> = emptyList()
        var novelTitle = ""

        if (novel != null) {
            novelTitle = novel.title
            chapterDao.getChaptersForNovel(novelId).collect { chapterEntities ->
                chapters = chapterEntities.map { it.toDomainModel() }
            }
        }

        val currentIndex = chapters.indexOfFirst { it.id == chapterId }
        val currentChapter = chapters.getOrNull(currentIndex)

        val prevChapter = if (currentIndex > 0) {
            chapters.getOrNull(currentIndex - 1)?.let {
                ChapterNavInfo(id = it.id, title = it.title, url = it.url)
            }
        } else null

        val nextChapter = if (currentIndex < chapters.size - 1) {
            chapters.getOrNull(currentIndex + 1)?.let {
                ChapterNavInfo(id = it.id, title = it.title, url = it.url)
            }
        } else null

        return ReaderChapter(
            novelId = novelId,
            novelTitle = novelTitle,
            chapterId = chapterId,
            chapterTitle = currentChapter?.title ?: "Chapter",
            chapterNumber = currentChapter?.number ?: (currentIndex + 1),
            content = content,
            totalChapters = chapters.size,
            prevChapter = prevChapter,
            nextChapter = nextChapter
        )
    }

    fun getNovelsWithProgress(): Flow<List<ReadingProgressEntity>> {
        return progressDao.getAllProgress()
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
            lineSpacing = 1.6f,
            font = settings.font.name
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

    private fun ReaderSettingsEntity.toDomainModel(): ReaderSettings {
        return ReaderSettings(
            fontSize = this.fontSize,
            theme = try {
                ReaderTheme.valueOf(this.theme)
            } catch (e: Exception) {
                ReaderTheme.LIGHT
            },
            font = try {
                ReaderFont.valueOf(this.font)
            } catch (e: Exception) {
                ReaderFont.SANS_SERIF
            }
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
}