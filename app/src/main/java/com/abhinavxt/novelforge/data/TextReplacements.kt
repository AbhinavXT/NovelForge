package com.abhinavxt.novelforge.data

/**
 * Text replacement while reading: the reader shows a paragraph with the
 * user's rules applied, while the stored chapter text stays untouched.
 *
 * Matching is the pronunciation dictionary's ([PronunciationRules]): whole
 * words, case-insensitive, longest rule first, one pass, symbols anywhere,
 * and an empty replacement removes the match.
 *
 * Highlights, find, formatting and the TTS sentence all point into the
 * stored text, so a replaced paragraph comes with a [Mapped] that converts
 * offsets between the stored text and what is shown.
 */
object TextReplacements {

    /** One replacement: [origStart, origEnd) in the stored text became [dispStart, dispEnd). */
    data class Edit(val origStart: Int, val origEnd: Int, val dispStart: Int, val dispEnd: Int)

    /** A paragraph as shown, and how its offsets relate to the stored text. */
    class Mapped(val original: String, val text: String, val edits: List<Edit>) {

        val isChanged: Boolean get() = edits.isNotEmpty()

        /**
         * A stored-text offset as a displayed one. An offset inside a
         * replaced stretch moves to the replacement's start ([isEnd] false)
         * or end ([isEnd] true), so a range touching it covers all of it.
         */
        fun toDisplay(offset: Int, isEnd: Boolean): Int =
            convert(offset, isEnd, { it.origStart }, { it.origEnd }, { it.dispStart }, { it.dispEnd }, text.length)

        /** A displayed offset as a stored-text one; the reverse of [toDisplay]. */
        fun toOriginal(offset: Int, isEnd: Boolean): Int =
            convert(offset, isEnd, { it.dispStart }, { it.dispEnd }, { it.origStart }, { it.origEnd }, original.length)

        /** A stored-text range [start, end) as a displayed one; null when nothing of it is left. */
        fun rangeToDisplay(start: Int, end: Int): IntRange? {
            val s = toDisplay(start, isEnd = false)
            val e = toDisplay(end, isEnd = true)
            return if (s < e) s until e else null
        }

        private inline fun convert(
            offset: Int,
            isEnd: Boolean,
            fromStart: (Edit) -> Int,
            fromEnd: (Edit) -> Int,
            toStart: (Edit) -> Int,
            toEnd: (Edit) -> Int,
            targetLength: Int
        ): Int {
            var delta = 0
            for (edit in edits) {
                val start = fromStart(edit)
                val end = fromEnd(edit)
                if (offset <= start) break
                if (offset >= end) {
                    delta = toEnd(edit) - end
                    continue
                }
                return if (isEnd) toEnd(edit) else toStart(edit)
            }
            return (offset + delta).coerceIn(0, targetLength)
        }
    }

    /** [text] with [rules] applied. Unchanged text comes back with no edits. */
    fun apply(rules: PronunciationRules.Compiled, text: String): Mapped {
        if (rules.isEmpty) return Mapped(text, text, emptyList())
        val matches = rules.matches(text)
        if (matches.isEmpty()) return Mapped(text, text, emptyList())

        val out = StringBuilder(text.length)
        val edits = ArrayList<Edit>(matches.size)
        var cursor = 0
        for ((range, rawReplacement) in matches) {
            var start = range.first
            var end = range.last + 1
            if (start < cursor) continue
            val replacement = if (rawReplacement.isEmpty()) {
                // A removal takes one side's spaces with it, so "a * b" reads
                // "a b" and "a *, b" reads "a, b" rather than leaving a gap.
                val (s, e) = removalBounds(text, start, end, cursor)
                start = s
                end = e
                ""
            } else {
                matchCase(text.substring(start, end), rawReplacement)
            }
            out.append(text, cursor, start)
            val dispStart = out.length
            out.append(replacement)
            edits += Edit(start, end, dispStart, out.length)
            cursor = end
        }
        out.append(text, cursor, text.length)
        return Mapped(text, out.toString(), edits)
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t'

    private fun removalBounds(text: String, start: Int, end: Int, floor: Int): Pair<Int, Int> {
        var before = start
        while (before > floor && isSpace(text[before - 1])) before--
        var after = end
        while (after < text.length && isSpace(text[after])) after++
        val spaceBefore = before < start
        val nextIsBreak = after > end || end == text.length || !text[end].isLetterOrDigit()
        return when {
            // "a * b", "a *, b", "a *" -> drop the spaces before it.
            spaceBefore && nextIsBreak -> before to end
            // "* a" at the start of the paragraph -> drop the spaces after it.
            start == 0 && after > end -> start to after
            else -> start to end
        }
    }

    /**
     * Rules match case-insensitively, so the replacement follows the case of
     * what it replaces: "HE" -> "SHE", and "He" at a sentence start -> "She"
     * for a rule written "he" -> "she". Anything else is used as typed.
     */
    internal fun matchCase(matched: String, replacement: String): String {
        val letters = matched.filter { it.isLetter() }
        if (letters.isEmpty() || replacement.isEmpty()) return replacement
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return replacement.uppercase()
        val first = replacement.first()
        return if (letters.first().isUpperCase() && first.isLowerCase()) {
            first.uppercaseChar() + replacement.substring(1)
        } else replacement
    }
}
