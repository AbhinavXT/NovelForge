package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// Download state for a single chapter
data class ChapterDownloadState(
    val chapterId: String,
    val status: DownloadStatus,
    val progress: Float = 0f
)

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

// Download state for a novel (bulk download)
data class NovelDownloadState(
    val novelId: String,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val isDownloading: Boolean,
    val currentChapter: String? = null
)

class DownloadManager(private val database: AppDatabase) {

    private val chapterDao = database.chapterDao()

    // Track active downloads
    private val _activeDownloads = MutableStateFlow<Map<String, ChapterDownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, ChapterDownloadState>> = _activeDownloads.asStateFlow()

    // Track novel bulk downloads
    private val _novelDownloadState = MutableStateFlow<NovelDownloadState?>(null)
    val novelDownloadState: StateFlow<NovelDownloadState?> = _novelDownloadState.asStateFlow()

    // Download a single chapter
    suspend fun downloadChapter(
        novelId: String,
        chapter: Chapter
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Update state to downloading
                updateChapterState(chapter.id, DownloadStatus.DOWNLOADING)

                // Get the source for this novel
                val source = SourceManager.getSourceFromNovelId(novelId)
                    ?: return@withContext false

                // Fetch chapter content
                val content = source.getChapterContent(chapter.url)

                if (content != null) {
                    // Save to database
                    chapterDao.updateDownloadStatus(
                        chapterId = chapter.id,
                        isDownloaded = true,
                        content = content,
                        downloadedAt = System.currentTimeMillis()
                    )

                    updateChapterState(chapter.id, DownloadStatus.DOWNLOADED)
                    true
                } else {
                    updateChapterState(chapter.id, DownloadStatus.FAILED)
                    false
                }
            } catch (e: Exception) {
                Logger.e("Error", e)
                updateChapterState(chapter.id, DownloadStatus.FAILED)
                false
            }
        }
    }

    // Download all chapters for a novel
    suspend fun downloadAllChapters(
        novelId: String,
        chapters: List<Chapter>,
        onProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        return withContext(Dispatchers.IO) {
            var downloadedCount = 0
            val total = chapters.size

            _novelDownloadState.value = NovelDownloadState(
                novelId = novelId,
                totalChapters = total,
                downloadedChapters = 0,
                isDownloading = true
            )

            for ((index, chapter) in chapters.withIndex()) {
                // Check if already downloaded
                val existingChapter = chapterDao.getChapterById(chapter.id)
                if (existingChapter?.isDownloaded == true) {
                    downloadedCount++
                    continue
                }

                // Update progress
                _novelDownloadState.value = _novelDownloadState.value?.copy(
                    downloadedChapters = downloadedCount,
                    currentChapter = chapter.title
                )

                // Download the chapter
                val success = downloadChapter(novelId, chapter)
                if (success) {
                    downloadedCount++
                }

                onProgress(downloadedCount, total)

                // Small delay to avoid overwhelming the server
                kotlinx.coroutines.delay(500)
            }

            _novelDownloadState.value = _novelDownloadState.value?.copy(
                downloadedChapters = downloadedCount,
                isDownloading = false,
                currentChapter = null
            )

            downloadedCount
        }
    }

    // Get downloaded chapter content
    suspend fun getDownloadedContent(chapterId: String): String? {
        return withContext(Dispatchers.IO) {
            chapterDao.getChapterById(chapterId)?.content
        }
    }

    // Check if chapter is downloaded
    suspend fun isChapterDownloaded(chapterId: String): Boolean {
        return withContext(Dispatchers.IO) {
            chapterDao.getChapterById(chapterId)?.isDownloaded == true
        }
    }

    // Get download count for a novel
    fun getDownloadedChapterCount(novelId: String): Flow<Int> {
        return chapterDao.getDownloadedChapterCount(novelId)
    }

    // Delete a single chapter download
    suspend fun deleteChapterDownload(chapterId: String) {
        withContext(Dispatchers.IO) {
            chapterDao.deleteDownload(chapterId)
        }
    }

    // Delete all downloads for a novel
    suspend fun deleteAllDownloads(novelId: String) {
        withContext(Dispatchers.IO) {
            chapterDao.deleteAllDownloadsForNovel(novelId)
        }
    }

    // Cancel ongoing download
    fun cancelDownload() {
        _novelDownloadState.value = _novelDownloadState.value?.copy(
            isDownloading = false
        )
    }

    private fun updateChapterState(chapterId: String, status: DownloadStatus) {
        val current = _activeDownloads.value.toMutableMap()
        current[chapterId] = ChapterDownloadState(chapterId, status)
        _activeDownloads.value = current
    }
}