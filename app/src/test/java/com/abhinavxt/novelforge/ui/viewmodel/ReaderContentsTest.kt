package com.abhinavxt.novelforge.ui.viewmodel

import com.abhinavxt.novelforge.data.epub.TocEntry
import com.abhinavxt.novelforge.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentsTest {

    private val chapters = (1..4).map { Chapter("c$it", it, "Chapter $it", "url$it") }

    @Test
    fun `the book's contents resolve to chapters, dropping ones that don't exist`() {
        val contents = ReaderContents.build(
            listOf(
                TocEntry("Part One", 0, 1, 0),
                TocEntry("", 1, 2, 0),             // blank title -> chapter's own
                TocEntry("A scene", 9, 2, 14),     // very deep -> capped
                TocEntry("Gone", 0, 99, 0)
            ),
            chapters
        )
        assertTrue(contents.fromBook)
        assertEquals(
            listOf(
                ReaderContentsEntry("Part One", 0, "c1", "url1", 0),
                ReaderContentsEntry("Chapter 2", 1, "c2", "url2", 0),
                ReaderContentsEntry("A scene", ReaderContents.MAX_DEPTH, "c2", "url2", 14)
            ),
            contents.entries
        )
    }

    @Test
    fun `without a table of contents the chapters are listed`() {
        val contents = ReaderContents.build(emptyList(), chapters)
        assertFalse(contents.fromBook)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), contents.entries.map { it.title })
        assertEquals(ReaderContents.EMPTY.entries, ReaderContents.build(emptyList(), emptyList()).entries)
    }

    @Test
    fun `the current entry follows the paragraph, and skipped chapters belong to the one before`() {
        val contents = ReaderContents.build(
            listOf(
                TocEntry("One", 0, 1, 0),
                TocEntry("Two", 0, 2, 0),
                TocEntry("Two, later", 1, 2, 10),
                TocEntry("Four", 0, 4, 3)
            ),
            chapters
        )
        assertEquals(1, contents.currentIndex("c2", 9))
        assertEquals(2, contents.currentIndex("c2", 10))
        assertEquals(2, contents.currentIndex("c3", 0))   // chapter 3 has no entry
        assertEquals(3, contents.currentIndex("c4", 0))   // before its entry's paragraph: still its chapter
        assertEquals(-1, contents.currentIndex("nope", 0))
    }
}
