package com.abhinavxt.novelforge.util

import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationExporterTest {

    private fun highlight(chapter: Int, title: String, text: String, note: String? = null) =
        HighlightEntity(
            novelId = "n", chapterId = "n_ch$chapter", chapterNumber = chapter, chapterTitle = title,
            paragraphIndex = 0, startOffset = 0, endOffset = text.length, selectedText = text, note = note
        )

    private fun bookmark(snippet: String, note: String) = BookmarkEntity(
        novelId = "n", chapterId = "n_ch1", chapterUrl = "", chapterNumber = 1, chapterTitle = "One",
        paragraphIndex = 0, textSnippet = snippet, note = note
    )

    @Test
    fun `every line of a multi-line note stays in the quote`() {
        val markdown = AnnotationExporter.buildMarkdown(
            "Book", listOf(highlight(1, "One", "text", "first\n\nsecond")), emptyList()
        )
        assertTrue(markdown, "> **Note:** first\n>\n> second\n" in markdown)
    }

    @Test
    fun `chapters sharing a title stay separate`() {
        val markdown = AnnotationExporter.buildMarkdown(
            "Book",
            listOf(
                highlight(1, "Interlude", "a"),
                highlight(2, "Other", "b"),
                highlight(3, "Interlude", "c")
            ),
            emptyList()
        )
        assertEquals(2, Regex("### Interlude").findAll(markdown).count())
        assertTrue(markdown.indexOf("> a") < markdown.indexOf("> b"))
        assertTrue(markdown.indexOf("> b") < markdown.indexOf("> c"))
    }

    @Test
    fun `long bookmark snippets are cut and short ones are not`() {
        val markdown = AnnotationExporter.buildMarkdown(
            "Book", emptyList(), listOf(bookmark("x".repeat(161), "n"), bookmark("short  \n", "n"))
        )
        assertTrue(markdown, "> ${"x".repeat(160)}…\n" in markdown)
        assertTrue(markdown, "> short\n" in markdown)
    }
}
