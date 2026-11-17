package com.abhinavxt.novelforge.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Bionic reading: the first part of every word drawn bold. It is a style on
 * ranges of the paragraph's own AnnotatedString, never a change to the text,
 * so every character offset (highlights, search, TTS, bookmarks) stays valid.
 * Scroll mode and paged mode (which measures the same string, bold changes
 * glyph widths) both go through [add].
 */
object BionicReading {

    /**
     * Bold ranges (end exclusive, as `start until end`) for [text]. A word is
     * a maximal run of letters and their combining marks, so apostrophes,
     * hyphens and digits end it.
     * Scripts written without spaces are left alone.
     */
    fun ranges(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!Character.isLetter(text[i])) { i++; continue }
            val start = i
            var unspaced = false
            while (i < n && (Character.isLetter(text[i]) || isMark(text[i]))) {
                if (!unspaced && isUnspacedScript(text[i])) unspaced = true
                i++
            }
            if (unspaced) continue
            // Never split a letter from the marks that follow it (Devanagari
            // vowel signs, combining accents): the bold part grows to take them.
            var end = start + prefixLength(i - start)
            while (end < i && isMark(text[end])) end++
            out.add(start until end)
        }
        return out
    }

    /** How many leading letters of a word of [length] letters are bold. */
    fun prefixLength(length: Int): Int = when {
        length <= 0 -> 0
        length <= 3 -> 1
        length == 4 -> 2
        else -> Math.ceil(0.4 * length).toInt()
    }

    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isUnspacedScript(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI -> true
        else -> false
    }

    /**
     * Bolds the word starts of the paragraph already appended to this
     * builder. Pass [ranges] computed once (remembered) by the caller.
     */
    fun AnnotatedString.Builder.add(ranges: List<IntRange>) {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        for (r in ranges) addStyle(bold, r.first, r.last + 1)
    }
}
