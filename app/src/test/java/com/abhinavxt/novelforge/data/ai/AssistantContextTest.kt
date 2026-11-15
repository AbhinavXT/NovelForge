package com.abhinavxt.novelforge.data.ai

import com.abhinavxt.novelforge.data.database.ChapterText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContextTest {

    private fun chapter(n: Int, vararg paragraphs: String) = ChapterText(n, paragraphs.joinToString("\n\n"))

    @Test
    fun storyNeverIncludesChaptersAfterThePosition() {
        val texts = listOf(chapter(1, "one"), chapter(2, "two"), chapter(3, "three"))
        val out = AssistantContext.storyPassages(texts, upToChapter = 2, upToParagraph = null)
        assertEquals(listOf(1, 2), out.map { it.chapterNumber })
    }

    @Test
    fun storyStopsAtTheParagraphInTheCurrentChapter() {
        val texts = listOf(chapter(1, "a"), chapter(2, "first", "second", "third"))
        val out = AssistantContext.storyPassages(texts, upToChapter = 2, upToParagraph = 1)
        assertEquals("first\n\nsecond", out.last().text)
        assertFalse(out.joinToString { it.text }.contains("third"))
    }

    @Test
    fun storyKeepsTheLatestChaptersWithinBudgetInReadingOrder() {
        val texts = (1..5).map { chapter(it, "x".repeat(100)) }
        val out = AssistantContext.storyPassages(texts, upToChapter = 5, upToParagraph = null, budget = 250)
        assertEquals(listOf(4, 5), out.map { it.chapterNumber })
        assertTrue(out.sumOf { it.text.length } <= 250)
    }

    @Test
    fun storyTakesTheTailOfTheChapterThatDoesNotFit() {
        val texts = listOf(chapter(1, "early ".repeat(10).trim(), "mid", "late"), chapter(2, "now"))
        val out = AssistantContext.storyPassages(texts, upToChapter = 2, upToParagraph = null, budget = 15)
        assertEquals(listOf(1, 2), out.map { it.chapterNumber })
        assertEquals("mid\n\nlate", out.first().text)
    }

    @Test
    fun storyWithNothingStoredIsEmpty() {
        assertTrue(AssistantContext.storyPassages(emptyList(), 3, null).isEmpty())
        assertTrue(AssistantContext.storyPassages(listOf(chapter(5, "later")), 3, null).isEmpty())
    }

    @Test
    fun characterPassagesAreWholeWordMentionsUpToThePositionOnly() {
        val texts = listOf(
            chapter(1, "Zhao arrived.", "Nobody else."),
            chapter(2, "Zhaos is not a match.", "Then zhao left."),
            chapter(3, "Zhao returned.")
        )
        val out = AssistantContext.characterPassages(texts, "Zhao", upToChapter = 2, upToParagraph = null)
        assertEquals(listOf("Zhao arrived.", "Then zhao left."), out.map { it.text })
    }

    @Test
    fun characterPassagesStopAtTheParagraphInTheCurrentChapter() {
        val texts = listOf(chapter(2, "Zhao one.", "filler", "Zhao two."))
        val out = AssistantContext.characterPassages(texts, "Zhao", upToChapter = 2, upToParagraph = 1)
        assertEquals(listOf("Zhao one."), out.map { it.text })
    }

    @Test
    fun characterBudgetKeepsFirstAppearanceThenMostRecentInOrder() {
        val texts = (1..6).map { chapter(it, "Zhao " + "x".repeat(95) + " $it") }
        val out = AssistantContext.characterPassages(texts, "Zhao", upToChapter = 6, upToParagraph = null, budget = 320)
        // About 100 characters each: the first, then the newest two fit after it.
        assertEquals(listOf(1, 5, 6), out.map { it.chapterNumber })
    }
}
