package com.abhinavxt.novelforge.data

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.database.PronunciationEntry
import com.abhinavxt.novelforge.data.database.TextReplacementEntity
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.util.Logger
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
    val readingStats: List<ReadingStatBackup>? = null,
    val textReplacements: List<TextReplacementBackup>? = null,
    val vocabulary: List<VocabularyBackup>? = null,
    val glossary: List<GlossaryBackup>? = null,
    /** Translated chapters' original text, so a restored translation can still be undone. */
    val chapterOriginals: List<ChapterOriginalBackup>? = null
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
    val lastUpdatedAt: Long,

    /**
     * Nullable with a default so backups written before this field existed
     * still deserialize — Gson leaves absent fields alone, and every older
     * backup simply restores a null.
     *
     * Worth carrying: without it, a restore would strip the library-folder
     * dedupe key from every scanned book, and the next scan would import
     * the whole folder a second time.
     */
    val sourceUri: String? = null,

    /**
     * Same nullable-with-default treatment as [sourceUri], and for the same
     * reason: older backups simply restore a null.
     *
     * Dropping it would be worse than dropping the URI. A restore that lost
     * every hash would leave the whole library invisible to duplicate
     * detection, and the next import of files the user already owns would
     * sail straight through.
     */
    val contentHash: String? = null,

    /**
     * Archive state, nullable for the same reason as the fields above.
     * Without these an archived book would restore as an ordinary book with
     * no text, and its fingerprint — the only proof a later restore from
     * its file lines up with its bookmarks — would be gone.
     */
    val archivedAt: Long? = null,
    val textFingerprint: String? = null,
    // v24 shelf status and per-book look; null in older backups.
    val readingStatus: String? = null,
    val readerSettingsJson: String? = null,
    // v25 book language; null in older backups.
    val language: String? = null,
    // v26 translate switch; null in older backups.
    val translate: Boolean? = null
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

/** [novelId] null = applies to every book. */
data class TextReplacementBackup(
    val novelId: String?,
    val find: String,
    val replace: String
)

data class GlossaryBackup(val novelId: String, val source: String, val target: String, val note: String)

data class ChapterOriginalBackup(
    val chapterId: String,
    val novelId: String,
    val original: String,
    val translatedHash: Int,
    val engine: String,
    val translatedAt: Long
)

/** A saved word with its review schedule. */
data class VocabularyBackup(
    val word: String,
    val definition: String,
    val sentence: String?,
    val novelId: String?,
    val novelTitle: String?,
    val chapterNumber: Int?,
    val box: Int,
    val dueAt: Long,
    val createdAt: Long
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
    var textReplacements: TextReplacementManager? = null,
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
        return "novel_forge_backup_$timestamp.json"
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
                    lastUpdatedAt = novel.lastUpdatedAt,
                    sourceUri = novel.sourceUri,
                    contentHash = novel.contentHash,
                    archivedAt = novel.archivedAt,
                    textFingerprint = novel.textFingerprint,
                    readingStatus = novel.readingStatus,
                    readerSettingsJson = novel.readerSettingsJson,
                    language = novel.language,
                    translate = novel.translate
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

            // Text replacement rules
            val textReplacementBackups = textReplacements?.getAllForBackup()?.map { rule ->
                TextReplacementBackup(novelId = rule.novelId, find = rule.find, replace = rule.replace)
            }

            val vocabularyBackups = repository.getAllVocabularyForBackup().map {
                VocabularyBackup(it.word, it.definition, it.sentence, it.novelId, it.novelTitle,
                    it.chapterNumber, it.box, it.dueAt, it.createdAt)
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
                readingStats = readingStatBackups,
                textReplacements = textReplacementBackups,
                vocabulary = vocabularyBackups,
                glossary = repository.getAllGlossaryForBackup().map {
                    GlossaryBackup(it.novelId, it.source, it.target, it.note)
                },
                chapterOriginals = repository.getAllChapterOriginalsForBackup().map {
                    ChapterOriginalBackup(it.chapterId, it.novelId, it.original, it.translatedHash, it.engine, it.translatedAt)
                }
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
                    lastUpdatedAt = novelBackup.lastUpdatedAt,
                    sourceUri = novelBackup.sourceUri,
                    contentHash = novelBackup.contentHash,
                    // An imported book restored without its text is an
                    // archived book in all but name; marking it so lets the
                    // library offer to bring the text back from the file.
                    archivedAt = novelBackup.archivedAt
                        ?: if (!includeDownloads && novelBackup.source == "local") {
                            System.currentTimeMillis()
                        } else null,
                    textFingerprint = novelBackup.textFingerprint,
                    readingStatus = novelBackup.readingStatus,
                    readerSettingsJson = novelBackup.readerSettingsJson,
                    language = novelBackup.language,
                    translate = novelBackup.translate == true
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

            // Restore text replacement rules
            try {
                backup.textReplacements?.let { rules ->
                    textReplacements?.insertForRestore(rules.map {
                        TextReplacementEntity(novelId = it.novelId, find = it.find, replace = it.replace)
                    })
                }
            } catch (e: Exception) {
                Logger.w("BackupManager", "Failed to restore text replacements: ${e.message}")
            }

            // Restore the translation glossary, and originals only along with the translated text they undo.
            try {
                repository.restoreTranslationData(
                    backup.glossary.orEmpty().map {
                        com.abhinavxt.novelforge.data.database.GlossaryTermEntity(
                            novelId = it.novelId, source = it.source, target = it.target, note = it.note
                        )
                    },
                    if (!includeDownloads) emptyList() else backup.chapterOriginals.orEmpty().map {
                        com.abhinavxt.novelforge.data.database.ChapterOriginalEntity(
                            it.chapterId, it.novelId, it.original, it.translatedHash, it.engine, it.translatedAt
                        )
                    }
                )
            } catch (e: Exception) {
                Logger.w("BackupManager", "Failed to restore translation data: ${e.message}")
            }

            // Restore vocabulary; a word already saved keeps its card.
            try {
                backup.vocabulary?.let { words ->
                    repository.insertVocabularyForRestore(words.map {
                        com.abhinavxt.novelforge.data.database.VocabularyEntity(
                            word = it.word, key = it.word.trim().lowercase(), definition = it.definition,
                            sentence = it.sentence, novelId = it.novelId, novelTitle = it.novelTitle,
                            chapterNumber = it.chapterNumber, box = it.box, dueAt = it.dueAt, createdAt = it.createdAt
                        )
                    })
                }
            } catch (e: Exception) {
                Logger.w("BackupManager", "Failed to restore vocabulary: ${e.message}")
            }

            // Restore reading stats
            var statsRestored = 0
            backup.readingStats?.forEach { stat ->
                try {
                    readingStatsTracker?.insertForRestore(
                        com.abhinavxt.novelforge.data.database.ReadingStatEvent(
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
            BackupResult.Error("Invalid backup file format. Please select a valid Novel Forge backup.")
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