package com.abhinavxt.novelforge.data

/** "Previously in…": what the reader sees on coming back to a book after a break. */
data class RecapData(
    val daysAway: Int,
    val chapterNumber: Int,
    /** The paragraph you stopped on, when its text is on the device. */
    val stoppedAt: String?,
    /** Codex names seen most in your last few chapters, most mentioned first. */
    val characters: List<String>,
    /** Your highlights from those chapters, latest first. */
    val highlights: List<String>
) {
    val isEmpty get() = stoppedAt == null && characters.isEmpty() && highlights.isEmpty()
}

object Recap {
    /** Shown after this long away. */
    const val AWAY_DAYS = 7

    /** Chapters looked back over, ending with the one you're on. */
    const val CHAPTERS_BACK = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun daysAway(lastReadAt: Long, now: Long): Int = ((now - lastReadAt) / DAY_MS).toInt()

    /**
     * Names (with the chapter each first appears in) ranked by whole-word mentions
     * in [texts]. Spoiler-safe: a name first seen after [ceiling] is never counted.
     */
    fun characters(names: List<Pair<String, Int>>, ceiling: Int, texts: List<String>, limit: Int = 6): List<String> {
        if (texts.isEmpty()) return emptyList()
        return names.asSequence()
            .filter { (_, first) -> first <= ceiling }
            .map { (name, _) ->
                val word = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])")
                name to texts.sumOf { word.findAll(it).count() }
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** The paragraph at [index], shortened to [max] characters. */
    fun stoppedAt(paragraphs: List<String>, index: Int, max: Int = 280): String? =
        paragraphs.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }
}
