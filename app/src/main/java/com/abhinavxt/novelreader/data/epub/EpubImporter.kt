package com.abhinavxt.novelreader.data.epub

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelreader.data.database.AppDatabase
import com.abhinavxt.novelreader.data.database.ChapterEntity
import com.abhinavxt.novelreader.data.database.NovelEntity
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles importing EPUB files into the app's database
 */
class EpubImporter(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val parser = EpubParser(context)

    /**
     * Import result sealed class
     */
    sealed class ImportResult {
        data class Success(val novelId: String, val title: String, val chapterCount: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Import an EPUB file from URI
     * @param uri URI from file picker
     * @return ImportResult indicating success or failure
     */
    suspend fun importEpub(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Parse the EPUB
            val epub = parser.parse(uri)
                ?: return@withContext ImportResult.Error("Failed to parse EPUB file. The file may be corrupted or in an unsupported format.")

            if (epub.chapters.isEmpty()) {
                return@withContext ImportResult.Error("No readable chapters found in this EPUB.")
            }

            // Step 2: Generate unique ID for this novel
            val novelId = "local_${UUID.randomUUID().toString().take(8)}"

            // Step 3: Save cover image if available
            val coverPath = epub.coverImage?.let { coverBytes ->
                saveCoverImage(novelId, coverBytes)
            }

            // Step 4: Create novel entity
            val novelEntity = NovelEntity(
                id = novelId,
                title = epub.title,
                author = epub.author,
                coverUrl = coverPath, // Local file path or null
                description = epub.description,
                source = "local", // Mark as local source
                status = "Completed", // EPUBs are typically complete
                totalChapters = epub.chapters.size,
                addedToLibraryAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis()
            )

            // Step 5: Create chapter entities (with content already stored)
            val chapterEntities = epub.chapters.map { chapter ->
                ChapterEntity(
                    id = "${novelId}_ch${chapter.order}",
                    novelId = novelId,
                    number = chapter.order,
                    title = chapter.title,
                    url = "local://$novelId/${chapter.order}", // Fake URL for local content
                    isDownloaded = true, // Content is already available
                    content = chapter.content, // Store content directly
                    downloadedAt = System.currentTimeMillis()
                )
            }

            // Step 6: Save to database
            novelDao.insertNovel(novelEntity)
            chapterDao.insertChapters(chapterEntities)

            ImportResult.Success(
                novelId = novelId,
                title = epub.title,
                chapterCount = epub.chapters.size
            )

        } catch (e: Exception) {
            Logger.e("Error", e)
            ImportResult.Error("Import failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Save cover image to internal storage
     * @return File path to saved image, or null if failed
     */
    private fun saveCoverImage(novelId: String, imageBytes: ByteArray): String? {
        return try {
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }

            val coverFile = File(coversDir, "$novelId.jpg")
            FileOutputStream(coverFile).use { fos ->
                fos.write(imageBytes)
            }

            coverFile.absolutePath
        } catch (e: Exception) {
            Logger.e("Error", e)
            null
        }
    }

    /**
     * Delete a local novel and its associated files
     */
    suspend fun deleteLocalNovel(novelId: String) = withContext(Dispatchers.IO) {
        // Delete cover image
        val coverFile = File(context.filesDir, "covers/$novelId.jpg")
        if (coverFile.exists()) {
            coverFile.delete()
        }

        // Database cleanup is handled by NovelRepository.removeFromLibrary()
    }
}