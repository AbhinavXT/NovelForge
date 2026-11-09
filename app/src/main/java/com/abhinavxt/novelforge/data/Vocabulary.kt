package com.abhinavxt.novelforge.data

/** Pure helpers for the vocabulary list: review scheduling and the example sentence. */
object Vocabulary {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Days until the next review after a right answer, by the box the card moves into. */
    val INTERVAL_DAYS = listOf(0, 1, 3, 7, 14, 30)

    /**
     * Leitner boxes: knowing a card moves it up a box and further out (1, 3, 7, 14,
     * then every 30 days); missing it sends it back to box 0, due again now.
     */
    fun next(box: Int, knewIt: Boolean, now: Long): Pair<Int, Long> {
        if (!knewIt) return 0 to now
        val up = (box + 1).coerceAtMost(INTERVAL_DAYS.lastIndex)
        return up to now + INTERVAL_DAYS[up] * DAY_MS
    }

    private val SENTENCE_END = Regex("(?<=[.!?…][\"”’)]?)\\s+")

    /**
     * The sentence holding the first whole-word use of [word] in [paragraphs],
     * trimmed to [max] characters, or null when the text doesn't use it.
     */
    fun sentenceWith(word: String, paragraphs: List<String>, max: Int = 300): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val paragraph = paragraphs.firstOrNull { pattern.containsMatchIn(it) } ?: return null
        val sentence = paragraph.split(SENTENCE_END).firstOrNull { pattern.containsMatchIn(it) } ?: paragraph
        return sentence.trim().let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }
    }

    /** What the card shows: up to three definitions, one per line. */
    fun definitionText(definitions: List<String>): String =
        definitions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3).joinToString("\n")
}
