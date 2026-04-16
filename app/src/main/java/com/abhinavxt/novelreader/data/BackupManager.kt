package com.abhinavxt.novelreader.data

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelreader.data.database.ChapterEntity
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.database.NovelEntity
import com.abhinavxt.novelreader.data.database.PronunciationEntry
import com.abhinavxt.novelreader.data.database.ReadingProgressEntity
import com.abhinavxt.novelreader.util.Logger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing the complete backup
 */
data class BackupData(
    val version: Int = BACKUP_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val novels: List<NovelBackup>,
    val chapters: List<ChapterBackup>,
    val readingProgress: List<ReadingProgressBackup>,
    val readerSettings: ReaderSettingsBackup?,
    val ttsSettings: TTSSettingsBackup?,
    val bookmarks: List<BookmarkBackup>? = null,
    val pronunciationEntries: List<PronunciationBackup>? = null,
    val readingStats: List<ReadingStatBackup>? = null
) {
    companion object {
        const val BACKUP_VERSION = 2
    }
}

data class NovelBackup(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String,
    val source: String,
    val sourceUrl: String,
    val status: String,
    val totalChapters: Int,
    val addedToLibraryAt: Long,
    val lastUpdatedAt: Long
)

data class ChapterBackup(
    val id: String,
    val novelId: String,
    val number: Int,
    val title: String,
    val url: String,
    val isDownloaded: Boolean,
    val content: String?,
    val downloadedAt: Long?
)

data class ReadingProgressBackup(
    val novelId: String,
    val currentChapterId: String,
    val currentChapterNumber: Int,
    val paragraphIndex: Int,
    val chaptersRead: Int,
    val lastReadAt: Long
)

data class ReaderSettingsBackup(
    val fontSize: Int,
    val theme: String,
    val lineSpacing: Float,
    val font: String
)

data class TTSSettingsBackup(
    val speed: Float,
    val pitch: Float,
    val voiceName: String?,
    val sentencePauseMs: Long,
    val paragraphPauseMs: Long,
    val volume: Float
)

data class BookmarkBackup(
    val id: Long,
    val novelId: String,
    val chapterId: String,
    val chapterUrl: String,
    val chapterNumber: Int,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val textSnippet: String,
    val note: String?,
    val createdAt: Long
)

data class PronunciationBackup(
    val word: String,
    val replacement: String
)

data class ReadingStatBackup(
    val novelId: String,
    val chapterId: String,
    val wordsRead: Int,
    val readingTimeMs: Long,
    val completedAt: Long
)

/**
 * Result of a backup/restore operation
 */
sealed class BackupResult {
    data class Success(val message: String) : BackupResult()
    data class Error(val message: String) : BackupResult()
}

/**
 * Manages backup and restore operations
 */
class BackupManager(
    private val context: Context,
    private val repository: NovelRepository,
    var pronunciationManager: PronunciationManager? = null,
    var readingStatsTracker: ReadingStatsTracker? = null
) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val ttsPrefs by lazy {
        context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Generate a filename for the backup
     */
    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "novel_reader_backup_$timestamp.json"
    }

    /**
     * Create a backup and write it to the given URI
     */
    suspend fun createBackup(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            Logger.d("BackupManager", "Starting backup creation...")

            // Get all data from database
            val novels = repository.getAllNovelsForBackup()
            val chapters = repository.getAllChaptersForBackup()
            val progress = repository.getAllProgressForBackup()
            val settings = repository.getReaderSettings()

            Logger.d("BackupManager", "Fetched ${novels.size} novels, ${chapters.size} chapters")

            // Convert to backup format
            val novelBackups = novels.map { novel ->
                NovelBackup(
                    id = novel.id,
                    title = novel.title,
                    author = novel.author,
                    coverUrl = novel.coverUrl,
                    description = novel.description,
                    source = novel.source,
                    sourceUrl = novel.id,
                    status = novel.status,
                    totalChapters = novel.totalChapters,
                    addedToLibraryAt = novel.addedToLibraryAt,
                    lastUpdatedAt = novel.lastUpdatedAt
                )
            }

            val chapterBackups = chapters.map { chapter ->
                ChapterBackup(
                    id = chapter.id,
                    novelId = chapter.novelId,
                    number = chapter.number,
                    title = chapter.title,
                    url = chapter.url,
                    isDownloaded = chapter.isDownloaded,
                    content = chapter.content,
                    downloadedAt = chapter.downloadedAt
                )
            }

            val progressBackups = progress.map { prog ->
                ReadingProgressBackup(
                    novelId = prog.novelId,
                    currentChapterId = prog.currentChapterId,
                    currentChapterNumber = prog.currentChapterNumber,
                    paragraphIndex = prog.paragraphIndex,
                    chaptersRead = prog.chaptersRead,
                    lastReadAt = prog.lastReadAt
                )
            }

            val settingsBackup = settings?.let {
                ReaderSettingsBackup(
                    fontSize = it.fontSize,
                    theme = it.theme.name,
                    lineSpacing = 1.6f,
                    font = it.font.name
                )
            }

            val ttsBackup = TTSSettingsBackup(
                speed = ttsPrefs.getFloat("tts_speed", 1.0f),
                pitch = ttsPrefs.getFloat("tts_pitch", 1.0f),
                voiceName = ttsPrefs.getString("tts_voice", null),
                sentencePauseMs = ttsPrefs.getLong("tts_sentence_pause", 0L),
                paragraphPauseMs = ttsPrefs.getLong("tts_paragraph_pause", 0L),
                volume = ttsPrefs.getFloat("tts_volume", 1.0f)
            )

            // Bookmarks
            val bookmarkBackups = repository.getAllBookmarksForBackup().map { bm ->
                BookmarkBackup(
                    id = bm.id,
                    novelId = bm.novelId,
                    chapterId = bm.chapterId,
                    chapterUrl = bm.chapterUrl,
                    chapterNumber = bm.chapterNumber,
                    chapterTitle = bm.chapterTitle,
                    paragraphIndex = bm.paragraphIndex,
                    textSnippet = bm.textSnippet,
                    note = bm.note,
                    createdAt = bm.createdAt
                )
            }

            // Pronunciation dictionary
            val pronunciationBackups = pronunciationManager?.getAllForBackup()?.map { entry ->
                PronunciationBackup(word = entry.word, replacement = entry.replacement)
            }

            // Reading stats
            val readingStatBackups = readingStatsTracker?.getAllForBackup()?.map { event ->
                ReadingStatBackup(
                    novelId = event.novelId,
                    chapterId = event.chapterId,
                    wordsRead = event.wordsRead,
                    readingTimeMs = event.readingTimeMs,
                    completedAt = event.completedAt
                )
            }

            // Create backup object
            val backup = BackupData(
                novels = novelBackups,
                chapters = chapterBackups,
                readingProgress = progressBackups,
                readerSettings = settingsBackup,
                ttsSettings = ttsBackup,
                bookmarks = bookmarkBackups,
                pronunciationEntries = pronunciationBackups,
                readingStats = readingStatBackups
            )

            // Write to file
            val jsonString = gson.toJson(backup)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Could not open output stream")

            val sizeKb = jsonString.length / 1024
            Logger.d("BackupManager", "Backup created successfully (${sizeKb}KB)")

            val bookmarkCount = bookmarkBackups.size
            val pronunciationCount = pronunciationBackups?.size ?: 0
            val statsCount = readingStatBackups?.size ?: 0

            BackupResult.Success(
                "Backup created successfully!\n" +
                        "${novels.size} novels, ${chapters.filter { it.isDownloaded }.size} downloaded chapters" +
                        (if (bookmarkCount > 0) ", $bookmarkCount bookmarks" else "") +
                        (if (pronunciationCount > 0) ", $pronunciationCount pronunciations" else "") +
                        (if (statsCount > 0) ", $statsCount reading sessions" else "")
            )
        } catch (e: Exception) {
            Logger.e("BackupManager", "Backup failed", e)
            BackupResult.Error("Backup failed: ${e.message}")
        }
    }

    /**
     * Restore from a backup file
     */
    suspend fun restoreBackup(uri: Uri, includeDownloads: Boolean = true): BackupResult = withContext(Dispatchers.IO) {
        try {
            Logger.d("BackupManager", "Starting restore from backup...")

            // Read the file
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("Could not open backup file")

            // Parse the backup
            val backup = gson.fromJson(jsonString, BackupData::class.java)

            Logger.d("BackupManager", "Parsed backup: ${backup.novels.size} novels, version ${backup.version}")

            // Validate version
            if (backup.version > BackupData.BACKUP_VERSION) {
                return@withContext BackupResult.Error(
                    "Backup was created with a newer version of the app. Please update the app first."
                )
            }

            // Restore novels
            var novelsRestored = 0
            var chaptersRestored = 0
            var downloadedRestored = 0

            for (novelBackup in backup.novels) {
                val novelEntity = NovelEntity(
                    id = novelBackup.id,
                    title = novelBackup.title,
                    author = novelBackup.author,
                    coverUrl = novelBackup.coverUrl,
                    description = novelBackup.description,
                    source = novelBackup.source,
                    status = novelBackup.status,
                    totalChapters = novelBackup.totalChapters,
                    addedToLibraryAt = novelBackup.addedToLibraryAt,
                    lastUpdatedAt = novelBackup.lastUpdatedAt
                )
                repository.insertNovelForRestore(novelEntity)
                novelsRestored++
            }

            // Restore chapters
            for (chapterBackup in backup.chapters) {
                val chapterEntity = ChapterEntity(
                    id = chapterBackup.id,
                    novelId = chapterBackup.novelId,
                    number = chapterBackup.number,
                    title = chapterBackup.title,
                    url = chapterBackup.url,
                    isDownloaded = if (includeDownloads) chapterBackup.isDownloaded else false,
                    content = if (includeDownloads) chapterBackup.content else null,
                    downloadedAt = if (includeDownloads) chapterBackup.downloadedAt else null
                )
                repository.insertChapterForRestore(chapterEntity)
                chaptersRestored++
                if (chapterBackup.isDownloaded && includeDownloads) {
                    downloadedRestored++
                }
            }

            // Restore reading progress
            for (progressBackup in backup.readingProgress) {
                val progressEntity = ReadingProgressEntity(
                    novelId = progressBackup.novelId,
                    currentChapterId = progressBackup.currentChapterId,
                    currentChapterNumber = progressBackup.currentChapterNumber,
                    paragraphIndex = progressBackup.paragraphIndex,
                    chaptersRead = progressBackup.chaptersRead,
                    lastReadAt = progressBackup.lastReadAt
                )
                repository.insertProgressForRestore(progressEntity)
            }

            // Restore reader settings
            backup.readerSettings?.let { settings ->
                repository.restoreReaderSettings(
                    fontSize = settings.fontSize,
                    theme = settings.theme,
                    font = settings.font
                )
            }

            // Restore TTS settings
            backup.ttsSettings?.let { tts ->
                ttsPrefs.edit()
                    .putFloat("tts_speed", tts.speed)
                    .putFloat("tts_pitch", tts.pitch)
                    .putString("tts_voice", tts.voiceName)
                    .putLong("tts_sentence_pause", tts.sentencePauseMs)
                    .putLong("tts_paragraph_pause", tts.paragraphPauseMs)
                    .putFloat("tts_volume", tts.volume)
                    .apply()
            }

            // Restore bookmarks
            var bookmarksRestored = 0
            backup.bookmarks?.forEach { bm ->
                try {
                    repository.insertBookmarkForRestore(
                        BookmarkEntity(
                            id = bm.id,
                            novelId = bm.novelId,
                            chapterId = bm.chapterId,
                            chapterUrl = bm.chapterUrl,
                            chapterNumber = bm.chapterNumber,
                            chapterTitle = bm.chapterTitle,
                            paragraphIndex = bm.paragraphIndex,
                            textSnippet = bm.textSnippet,
                            note = bm.note,
                            createdAt = bm.createdAt
                        )
                    )
                    bookmarksRestored++
                } catch (e: Exception) {
                    Logger.w("BackupManager", "Failed to restore bookmark: ${e.message}")
                }
            }

            // Restore pronunciation dictionary
            var pronunciationsRestored = 0
            backup.pronunciationEntries?.forEach { entry ->
                try {
                    pronunciationManager?.insertForRestore(
                        PronunciationEntry(word = entry.word, replacement = entry.replacement)
                    )
                    pronunciationsRestored++
                } catch (e: Exception) {
                    Logger.w("BackupManager", "Failed to restore pronunciation: ${e.message}")
                }
            }

            // Restore reading stats
            var statsRestored = 0
            backup.readingStats?.forEach { stat ->
                try {
                    readingStatsTracker?.insertForRestore(
                        com.abhinavxt.novelreader.data.database.ReadingStatEvent(
                            novelId = stat.novelId,
                            chapterId = stat.chapterId,
                            wordsRead = stat.wordsRead,
                            readingTimeMs = stat.readingTimeMs,
                            completedAt = stat.completedAt
                        )
                    )
                    statsRestored++
                } catch (e: Exception) {
                    Logger.w("BackupManager", "Failed to restore reading stat: ${e.message}")
                }
            }

            Logger.d("BackupManager", "Restore completed successfully")

            BackupResult.Success(
                "Restore completed!\n" +
                        "$novelsRestored novels, $chaptersRestored chapters" +
                        (if (downloadedRestored > 0) ", $downloadedRestored downloaded" else "") +
                        (if (bookmarksRestored > 0) ", $bookmarksRestored bookmarks" else "") +
                        (if (pronunciationsRestored > 0) ", $pronunciationsRestored pronunciations" else "") +
                        (if (statsRestored > 0) ", $statsRestored reading sessions" else "")
            )
        } catch (e: com.google.gson.JsonSyntaxException) {
            Logger.e("BackupManager", "Invalid backup file format", e)
            BackupResult.Error("Invalid backup file format. Please select a valid Novel Reader backup.")
        } catch (e: Exception) {
            Logger.e("BackupManager", "Restore failed", e)
            BackupResult.Error("Restore failed: ${e.message}")
        }
    }

    /**
     * Get backup info without restoring
     */
    suspend fun getBackupInfo(uri: Uri): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: return@withContext null

            val backup = gson.fromJson(jsonString, BackupData::class.java)

            val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.US)
            val createdDate = dateFormat.format(Date(backup.createdAt))

            val downloadedChapters = backup.chapters.count { it.isDownloaded }
            val totalSize = jsonString.length

            BackupInfo(
                novelCount = backup.novels.size,
                chapterCount = backup.chapters.size,
                downloadedChapterCount = downloadedChapters,
                bookmarkCount = backup.bookmarks?.size ?: 0,
                pronunciationCount = backup.pronunciationEntries?.size ?: 0,
                readingStatsCount = backup.readingStats?.size ?: 0,
                createdAt = createdDate,
                version = backup.version,
                sizeBytes = totalSize.toLong()
            )
        } catch (e: Exception) {
            Logger.e("BackupManager", "Failed to read backup info", e)
            null
        }
    }
}

/**
 * Information about a backup file
 */
data class BackupInfo(
    val novelCount: Int,
    val chapterCount: Int,
    val downloadedChapterCount: Int,
    val bookmarkCount: Int = 0,
    val pronunciationCount: Int = 0,
    val readingStatsCount: Int = 0,
    val createdAt: String,
    val version: Int,
    val sizeBytes: Long
) {
    val sizeFormatted: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
        }
}