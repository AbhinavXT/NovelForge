package com.abhinavxt.novelforge.ui

import com.abhinavxt.novelforge.ui.components.fitQuote
import com.abhinavxt.novelforge.ui.components.quoteAuthorOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCardFitTest {

    @Test
    fun shortQuoteKeepsTextAndLargestSize() {
        val fit = fitQuote("  Not all who wander\nare lost.  ")
        assertEquals("Not all who wander are lost.", fit.text)
        assertEquals(34, fit.fontSizeSp)
    }

    @Test
    fun sizeStepsDownAsQuoteGrows() {
        val sizes = listOf(50, 120, 250, 400, 600).map { fitQuote("a".repeat(it)).fontSizeSp }
        assertEquals(sizes.sortedDescending(), sizes)
        assertEquals(5, sizes.distinct().size)
    }

    @Test
    fun quoteAtLimitIsNotTruncated() {
        val text = "a".repeat(600)
        assertEquals(text, fitQuote(text).text)
    }

    @Test
    fun longQuoteIsCutAtWordBoundaryWithEllipsis() {
        val text = "word ".repeat(200).trim()
        val fit = fitQuote(text)
        assertTrue(fit.text.endsWith("…"))
        assertTrue(fit.text.length <= 601)
        assertTrue(fit.text.removeSuffix("…").split(" ").all { it == "word" })
    }

    @Test
    fun trailingPunctuationIsDroppedBeforeEllipsis() {
        val text = "x".repeat(594) + " end, " + "tail words here"
        assertEquals("x".repeat(594) + " end…", fitQuote(text).text)
    }

    @Test
    fun authorHiddenWhenUnknownOrBlank() {
        assertNull(quoteAuthorOrNull(null))
        assertNull(quoteAuthorOrNull("  "))
        assertNull(quoteAuthorOrNull("Unknown"))
        assertNull(quoteAuthorOrNull("unknown"))
        assertEquals("Ursula K. Le Guin", quoteAuthorOrNull(" Ursula K. Le Guin "))
    }
}
