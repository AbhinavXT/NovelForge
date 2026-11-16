package com.abhinavxt.novelforge.ui.components

import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BionicReadingTest {

    private fun bold(text: String) = BionicReading.ranges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun prefixLengthsFollowTheRule() {
        assertEquals(listOf("I"), bold("I"))
        assertEquals(listOf("t"), bold("to"))
        assertEquals(listOf("t"), bold("the"))
        assertEquals(listOf("wo"), bold("word"))
        assertEquals(listOf("re"), bold("reads"))
        assertEquals(listOf("rea"), bold("reader"))
        assertEquals(listOf("extrao"), bold("extraordinary"))
    }

    @Test
    fun punctuationDigitsAndHyphensSplitWords() {
        assertEquals(listOf("d", "t"), bold("don't"))
        assertEquals(listOf("we", "kn"), bold("well-known"))
        assertEquals(listOf("a"), bold("a 123 !?"))
    }

    @Test
    fun emptyAndSymbolOnlyTextHaveNoRanges() {
        assertTrue(BionicReading.ranges("").isEmpty())
        assertTrue(BionicReading.ranges("... 42 ---").isEmpty())
    }

    @Test
    fun scriptsWrittenWithoutSpacesAreSkipped() {
        assertTrue(BionicReading.ranges("你好世界").isEmpty())
        assertTrue(BionicReading.ranges("ひらがなカタカナ").isEmpty())
        assertTrue(BionicReading.ranges("한국어").isEmpty())
        assertTrue(BionicReading.ranges("สวัสดี").isEmpty())
        assertEquals(listOf("H"), bold("你好 Hi"))
    }

    @Test
    fun rangesStayInsideTheStringAndDoNotOverlap() {
        val text = "Élan vital, naïve café: x y zed… “Quoted” end"
        var prevEnd = -1
        for (r in BionicReading.ranges(text)) {
            assertTrue(r.first > prevEnd)
            assertTrue(r.first >= 0 && r.last < text.length)
            prevEnd = r.last
        }
    }

    @Test
    fun addingSpansNeverChangesTheText() {
        val text = "Hello, world! It's a bionic test."
        val built = buildAnnotatedString {
            append(text)
            with(BionicReading) { add(ranges(text)) }
        }
        assertEquals(text, built.text)
        assertEquals(BionicReading.ranges(text).size, built.spanStyles.size)
    }

    @Test
    fun aWordWithCombiningMarksStaysOneWord() {
        // "किताब" is one word of five chars (two of them vowel signs), not three.
        val text = "\u0915\u093F\u0924\u093E\u092C"
        val ranges = BionicReading.ranges(text)
        assertEquals(1, ranges.size)
        // Prefix of 2 chars (letter + its vowel sign); never ends before a mark.
        assertEquals(0 until 2, ranges[0])
    }

    @Test
    fun theBoldPartNeverEndsBetweenALetterAndItsMark() {
        // "e" + combining acute, then "t": 3 chars, prefix 1 grows to 2.
        assertEquals(listOf(0 until 2), BionicReading.ranges("e\u0301t"))
    }
}
