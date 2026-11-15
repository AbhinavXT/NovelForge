package com.abhinavxt.novelforge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.abhinavxt.novelforge.data.epub.BlockStyle

/**
 * The drop cap: the first letter of a chapter's first body paragraph drawn
 * large. It is a style on that one character inside the paragraph's own
 * AnnotatedString, never a split of the text, so every character offset
 * (highlights, search, TTS sentence, selection) stays valid. Scroll mode and
 * paged mode (which measures the same string) both go through [add].
 */
object DropCap {

    /** Size of the initial relative to the body text. */
    const val SCALE = 3.2f

    private val OPENING_MARKS = setOf('"', '\'', '“', '‘', '«', '‹', '„', '‚', '(', '[')

    /**
     * Which paragraph of a chapter gets the initial, or null for none.
     * Blank lines, a line repeating the chapter [title] and headings are
     * skipped (they are not the body); a centred first block means this is
     * not running prose, so there is no drop cap.
     */
    fun paragraphIndex(paragraphs: List<String>, title: String, blockOf: (Int) -> BlockStyle?): Int? {
        val heading = title.trim()
        for (i in paragraphs.indices) {
            val text = paragraphs[i].trim()
            if (text.isEmpty()) continue
            if (heading.isNotEmpty() && text.equals(heading, ignoreCase = true)) continue
            return when (blockOf(i)) {
                BlockStyle.HEADING -> continue
                BlockStyle.CENTER -> null
                else -> if (looksLikeTitleLine(text)) continue else i.takeIf { initialIndex(paragraphs[i]) != null }
            }
        }
        return null
    }

    private val SENTENCE_ENDS = setOf('.', '!', '?', '…', '"', '”', '’', '\'', '»', ':', ';', ',', '—', '-')

    /**
     * Web chapters usually open with their own title as plain text
     * ("Chapter 009 Cheaters") worded differently from the title in the
     * chapter list, so matching the two misses it. A short line that does
     * not end like a sentence is taken for such a title; a short real
     * opening ("Rain.") ends in punctuation and keeps its initial.
     */
    fun looksLikeTitleLine(text: String): Boolean {
        val line = text.trim()
        return line.length <= MAX_TITLE_LENGTH && line.isNotEmpty() && line.last() !in SENTENCE_ENDS
    }

    private const val MAX_TITLE_LENGTH = 80

    /**
     * Where the initial letter sits in [text]: the first character, or the
     * one after an opening quote mark. Null when that character is not a
     * letter (a digit, a dash, a scene-break symbol).
     */
    fun initialIndex(text: String): Int? {
        val at = if (text.isNotEmpty() && text[0] in OPENING_MARKS) 1 else 0
        return at.takeIf { it < text.length && text[it].isLetter() }
    }

    /**
     * Styles the initial of [text] (the paragraph already appended to this
     * builder). [color] is plain black in e-ink, where a tint would dither.
     */
    fun AnnotatedString.Builder.add(text: String, color: Color) {
        val at = initialIndex(text) ?: return
        addStyle(SpanStyle(fontSize = SCALE.em, fontWeight = FontWeight.SemiBold, color = color), at, at + 1)
    }
}
