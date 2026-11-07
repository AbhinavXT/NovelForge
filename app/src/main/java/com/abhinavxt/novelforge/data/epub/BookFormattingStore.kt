package com.abhinavxt.novelforge.data.epub

import android.content.Context
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterFormattingEntity
import com.abhinavxt.novelforge.data.database.TocEntryEntity
import com.abhinavxt.novelforge.util.Logger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps an imported book's formatting, pictures and table of contents.
 *
 * Formatting and pictures belong with the text: they are written when the
 * text is, dropped when the book is archived, and come back when it is
 * restored, all from the same parse of the file. The table of contents
 * belongs with the chapter list, which archiving keeps, so it stays.
 */
class BookFormattingStore(
    context: Context,
    database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val appContext = context.applicationContext
    private val dao = database.bookFormattingDao()
    private val gson = Gson()

    /**
     * Replaces the book's formatting and pictures with those in [book].
     *
     * @param chapterIdOf a chapter number -> the id of its row
     */
    suspend fun saveFormatting(novelId: String, book: EpubBook, chapterIdOf: (Int) -> String) =
        withContext(Dispatchers.IO) {
            clearFormatting(novelId)
            val withFormatting = book.chapters.filter { it.formatting != null }
            if (withFormatting.isEmpty()) return@withContext

            // Pictures get short local names; the formatting is rewritten to
            // point at them. One that can't be written is left out.
            val dir = imagesDir(appContext, novelId)
            val names = mutableMapOf<String, String>()
            book.images.entries.forEachIndexed { i, (path, bytes) ->
                val name = "$i.${extensionOf(path)}"
                try {
                    dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    names[path] = name
                } catch (e: Exception) {
                    Logger.e(TAG, "Could not save image $path", e)
                }
            }

            val rows = withFormatting.map { chapter ->
                val formatting = chapter.formatting!!
                val local = formatting.copy(
                    images = formatting.images.mapNotNull { image ->
                        names[image.path]?.let { image.copy(path = it) }
                    }
                )
                ChapterFormattingEntity(
                    chapterId = chapterIdOf(chapter.order),
                    novelId = novelId,
                    json = gson.toJson(local)
                )
            }
            dao.insertFormatting(rows)
        }

    suspend fun saveToc(novelId: String, toc: List<TocEntry>) = withContext(Dispatchers.IO) {
        dao.deleteTocForNovel(novelId)
        if (toc.isEmpty()) return@withContext
        dao.insertToc(toc.mapIndexed { i, entry ->
            TocEntryEntity(novelId, i, entry.title, entry.depth, entry.chapter, entry.paragraph)
        })
    }

    /** Drops what goes with the text: formatting rows and pictures. */
    suspend fun clearFormatting(novelId: String) = withContext(Dispatchers.IO) {
        dao.deleteFormattingForNovel(novelId)
        imagesDir(appContext, novelId).deleteRecursively()
    }

    /** Everything, for a book leaving the library. */
    suspend fun clearAll(novelId: String) {
        clearFormatting(novelId)
        withContext(Dispatchers.IO) { dao.deleteTocForNovel(novelId) }
    }

    suspend fun getFormatting(chapterId: String): ChapterFormatting? = withContext(Dispatchers.IO) {
        val json = dao.getFormattingJson(chapterId) ?: return@withContext null
        try {
            gson.fromJson(json, ChapterFormatting::class.java)?.sanitized()
        } catch (e: Exception) {
            Logger.e(TAG, "Unreadable formatting for $chapterId", e)
            null
        }
    }

    fun imagesDir(novelId: String): File = imagesDir(appContext, novelId)

    suspend fun getToc(novelId: String): List<TocEntry> = withContext(Dispatchers.IO) {
        dao.getToc(novelId).map { TocEntry(it.title, it.depth, it.chapterNumber, it.paragraph) }
    }

    /**
     * Gson fills absent fields with null whatever Kotlin says, so a row
     * written by a later version with fewer fields can't crash the reader.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun ChapterFormatting.sanitized() = ChapterFormatting(
        spans = (spans ?: emptyList()).filter { it.style != null },
        blocks = (blocks ?: emptyList()).filter { it.style != null },
        images = (images ?: emptyList()).filter { it.path != null },
        notes = (notes ?: emptyList()).filter { it.text != null }
    )

    companion object {
        private const val TAG = "BookFormattingStore"

        fun imagesDir(context: Context, novelId: String): File =
            File(context.filesDir, "book_images/$novelId")

        private val KNOWN_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp")

        private fun extensionOf(path: String): String =
            path.substringAfterLast('.', "").lowercase().takeIf { it in KNOWN_EXTENSIONS } ?: "img"
    }
}
