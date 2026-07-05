package com.abhinavxt.novelreader.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.database.HighlightEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports a novel's highlights, annotations, and bookmark notes to a
 * Markdown file and opens the system share sheet (Phase 7).
 *
 * Uses the app's existing FileProvider (cache dir is already covered
 * by its paths config — same mechanism as the stats share card).
 */
object AnnotationExporter {

    sealed class ExportResult {
        object Shared : ExportResult()
        object Empty : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    suspend fun exportAndShare(
        context: Context,
        repository: NovelRepository,
        novelId: String,
        novelTitle: String
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            val highlights = repository.getHighlightsForNovelOnce(novelId)
            val bookmarks = repository.getBookmarksForNovelOnce(novelId)
                .filter { !it.note.isNullOrBlank() }

            if (highlights.isEmpty() && bookmarks.isEmpty()) {
                return@withContext ExportResult.Empty
            }

            val markdown = buildMarkdown(novelTitle, highlights, bookmarks)

            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = novelTitle
                .replace(Regex("[^A-Za-z0-9 _-]"), "")
                .trim()
                .take(60)
                .ifEmpty { "novel" }
                .replace(" ", "_")
            val file = File(exportsDir, "${safeTitle}_highlights.md")
            file.writeText(markdown)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$novelTitle — highlights & notes")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Export highlights").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            ExportResult.Shared
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("AnnotationExporter", "Export failed", e)
            ExportResult.Error(e.message ?: "Export failed")
        }
    }

    private fun buildMarkdown(
        novelTitle: String,
        highlights: List<HighlightEntity>,
        bookmarks: List<BookmarkEntity>
    ): String {
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val sb = StringBuilder()

        sb.appendLine("# $novelTitle — Highlights & Notes")
        sb.appendLine()
        sb.appendLine("_Exported ${dateFormat.format(Date())} from NovelForge_")
        sb.appendLine()

        if (highlights.isNotEmpty()) {
            sb.appendLine("## Highlights")
            sb.appendLine()
            // Group by chapter, preserving DAO order (chapter, paragraph)
            highlights
                .groupBy { it.chapterTitle }
                .forEach { (chapterTitle, chapterHighlights) ->
                    sb.appendLine("### $chapterTitle")
                    sb.appendLine()
                    chapterHighlights.forEach { h ->
                        sb.appendLine("> ${h.selectedText.trim()}")
                        if (!h.note.isNullOrBlank()) {
                            sb.appendLine(">")
                            sb.appendLine("> **Note:** ${h.note!!.trim()}")
                        }
                        sb.appendLine()
                    }
                }
        }

        if (bookmarks.isNotEmpty()) {
            sb.appendLine("## Bookmark Notes")
            sb.appendLine()
            bookmarks
                .groupBy { it.chapterTitle }
                .forEach { (chapterTitle, chapterBookmarks) ->
                    sb.appendLine("### $chapterTitle")
                    sb.appendLine()
                    chapterBookmarks.forEach { b ->
                        val snippet = b.textSnippet.trim().take(160)
                        sb.appendLine("> $snippet${if (b.textSnippet.length > 160) "…" else ""}")
                        sb.appendLine(">")
                        sb.appendLine("> **Note:** ${b.note!!.trim()}")
                        sb.appendLine()
                    }
                }
        }

        return sb.toString()
    }
}
