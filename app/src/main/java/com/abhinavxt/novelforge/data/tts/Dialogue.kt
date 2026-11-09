package com.abhinavxt.novelforge.data.tts

import com.abhinavxt.novelforge.data.codex.NameExtractor

/**
 * Splits chapter text into what TTS speaks, one piece at a time, and works out who says each quote.
 *
 * Without voices it's one piece per sentence, as TTS has always spoken. With voices, a sentence like
 * `"Go," said Li Wei.` becomes two pieces, the quote and the tag, so each can have its own voice.
 * Pieces keep their sentence's index, so the reader still highlights the whole sentence.
 */
object Dialogue {

    data class Piece(
        val text: String,
        val paragraph: Int,
        /** Index of the sentence within its paragraph, the reader's highlighting unit. */
        val sentence: Int,
        /** Last piece of its paragraph. */
        val paragraphEnd: Boolean,
        val quoted: Boolean = false,
        /** Who says it, for a quoted piece whose speaker could be told; else null. */
        val speaker: String? = null
    )

    private val SENTENCE_GAP = Regex("(?<=[.!?])\\s+")
    private val CLOSERS = mapOf('“' to '”', '「' to '」', '『' to '』', '"' to '"')
    private val WORD = Regex("[\\p{L}']+")

    /** [names]: the book's characters, from the codex. [voiced] false keeps whole sentences. */
    fun pieces(text: String, names: List<String> = emptyList(), voiced: Boolean = false): List<Piece> {
        val paragraphs = text.split(Regex("\n\n+|\n")).map { it.trim() }.filter { it.isNotBlank() }
        val byLength = names.filter { it.isNotBlank() }.sortedByDescending { it.length }
        val recent = ArrayDeque<String>()   // speakers of the last dialogue paragraphs, newest last
        val out = mutableListOf<Piece>()

        paragraphs.forEachIndexed { p, paragraph ->
            val sentences = paragraph.split(SENTENCE_GAP).map { it.trim() }.filter { it.isNotBlank() }
            if (!voiced) {
                sentences.forEachIndexed { s, sentence -> out += Piece(sentence, p, s, s == sentences.lastIndex) }
                return@forEachIndexed
            }

            // Quotes can run across sentences ("Wait. Don't go."), so the open/closed state carries over.
            var closer: Char? = null
            val split = sentences.map { sentence ->
                val parts = mutableListOf<Pair<String, Boolean>>()
                val current = StringBuilder()
                for (c in sentence) {
                    val opening = closer == null && c in CLOSERS
                    val closing = closer != null && c == closer
                    if (opening) {
                        parts += current.toString() to false
                        current.clear()
                        closer = CLOSERS.getValue(c)
                    }
                    current.append(c)
                    if (closing) {
                        parts += current.toString() to true
                        current.clear()
                        closer = null
                    }
                }
                parts += current.toString() to (closer != null)
                parts.map { (t, q) -> t.trim() to q }.filter { (t, _) -> t.any { it.isLetterOrDigit() } }
            }

            val hasQuote = split.any { parts -> parts.any { it.second } }
            val narration = split.flatten().filter { !it.second }.joinToString(" ") { it.first }
            val speaker = if (hasQuote) speakerOf(narration, byLength, recent) else null
            if (hasQuote) {
                recent.addLast(speaker.orEmpty())
                if (recent.size > 2) recent.removeFirst()
            }

            split.forEachIndexed { s, parts ->
                parts.forEachIndexed { i, (t, quoted) ->
                    val last = s == split.lastIndex && i == parts.lastIndex
                    out += Piece(t, p, s, last, quoted, if (quoted) speaker else null)
                }
            }
        }
        return out
    }

    /**
     * Who speaks this paragraph's quotes: a character next to a speech verb ("Li Wei said",
     * "asked Chen"), else the only character the narration names, else, in a back-and-forth with
     * no narration, the one who spoke two lines ago.
     */
    private fun speakerOf(narration: String, names: List<String>, recent: ArrayDeque<String>): String? {
        if (narration.isBlank()) {
            val (before, last) = recent.getOrNull(recent.size - 2) to recent.lastOrNull()
            return before?.takeIf { it.isNotEmpty() && it != last }
        }
        val words = WORD.findAll(narration).toList()
        val mentioned = mutableListOf<Pair<Int, String>>()   // position, name
        for (name in names) {
            Regex("(?<![\\p{L}])${Regex.escape(name)}(?![\\p{L}])").findAll(narration).forEach { m ->
                if (mentioned.none { (pos, other) -> m.range.first in pos until pos + other.length }) {
                    mentioned += m.range.first to name
                }
            }
        }
        mentioned.sortBy { it.first }
        for ((pos, name) in mentioned) {
            val at = words.indexOfFirst { it.range.first >= pos }
            if (at < 0) continue
            val nameWords = WORD.findAll(name).count()
            val after = words.drop(at + nameWords).take(3)
            val before = words.subList(maxOf(0, at - 2), at)
            if ((after + before).any { it.value.lowercase() in NameExtractor.SPEECH_VERBS }) return name
        }
        return mentioned.map { it.second }.distinct().singleOrNull()
    }
}
