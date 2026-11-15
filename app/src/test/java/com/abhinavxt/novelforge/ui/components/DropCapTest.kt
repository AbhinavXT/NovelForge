package com.abhinavxt.novelforge.ui.components

import com.abhinavxt.novelforge.data.epub.BlockStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropCapTest {

    private fun pick(paragraphs: List<String>, title: String = "Chapter 1", blocks: Map<Int, BlockStyle> = emptyMap()) =
        DropCap.paragraphIndex(paragraphs, title) { blocks[it] }

    @Test
    fun theFirstBodyParagraphGetsIt() {
        assertEquals(0, pick(listOf("It was a dark night.", "Rain fell.")))
    }

    @Test
    fun blankLinesAndARepeatedTitleAreSkipped() {
        assertEquals(2, pick(listOf("", "chapter 1", "The door opened.")))
    }

    @Test
    fun aHeadingIsSkippedButACentredBlockMeansNoDropCap() {
        assertEquals(1, pick(listOf("Prologue", "Once upon a time."), blocks = mapOf(0 to BlockStyle.HEADING)))
        assertNull(pick(listOf("* * *", "Text."), blocks = mapOf(0 to BlockStyle.CENTER)))
    }

    @Test
    fun aParagraphNotStartingWithALetterGetsNone() {
        assertNull(pick(listOf("1. Pack the bags.", "Then go.")))
        assertNull(pick(listOf("— No, he said.")))
    }

    @Test
    fun nothingToDecorateInAnEmptyChapter() {
        assertNull(pick(emptyList()))
        assertNull(pick(listOf("", "  ")))
    }

    @Test
    fun anOpeningQuoteStaysNormalAndTheLetterAfterItIsTheInitial() {
        assertEquals(1, DropCap.initialIndex("“Who goes there?”"))
        assertEquals(1, DropCap.initialIndex("\"Hello"))
        assertEquals(0, DropCap.initialIndex("Hello"))
    }

    @Test
    fun aQuoteFollowedByANonLetterHasNoInitial() {
        assertNull(DropCap.initialIndex("“...and then"))
        assertNull(DropCap.initialIndex("\""))
        assertNull(DropCap.initialIndex(""))
    }

    @Test
    fun `a title line worded differently from the chapter title is skipped`() {
        val paragraphs = listOf("Chapter 009 Cheaters", "“Majara,” intoned Zorian, finishing the spell.")
        assertEquals(1, DropCap.paragraphIndex(paragraphs, "9. Cheaters") { null })
    }

    @Test
    fun `a short opening sentence keeps its initial`() {
        assertEquals(0, DropCap.paragraphIndex(listOf("Rain.", "It had rained for days."), "One") { null })
    }
}
