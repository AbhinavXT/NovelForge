package com.abhinavxt.novelforge.data

/**
 * Compilation and application of pronunciation dictionary rules.
 *
 * Pure (String in, String out) so it can be unit-tested without Room or a
 * TTS engine. [PronunciationManager] owns persistence and caching; this owns
 * the matching semantics.
 *
 * Three behaviours worth knowing:
 *
 *  - An EMPTY replacement means "don't speak this". That is how symbol
 *    skipping works — no separate rule type, no schema change.
 *
 *  - Rules whose word contains no letters or digits (`*`, `~`, `##`) are
 *    matched LITERALLY, anywhere. Word-boundary matching is wrong for
 *    symbols: in "Hello *world*" the asterisk sits directly against a letter,
 *    so a boundary-anchored pattern never fires. Word rules keep boundaries so
 *    "Li" does not match inside "Lisa".
 *
 *  - All rules are applied in a SINGLE pass. Applying them one after another
 *    over the accumulated result let a replacement be re-matched by a later
 *    rule: with Li→Lee and Lee→Leigh, "Li" came out as "Leigh". One combined
 *    pattern means each character of the input is consumed at most once.
 */
object PronunciationRules {

    /**
     * Symbols that TTS engines commonly read aloud by name ("asterisk",
     * "underscore") and that carry no meaning when spoken. Offered as a
     * one-tap preset.
     *
     * Deliberately EXCLUDES . , ! ? ; : and quotes. Those drive prosody --
     * removing "?" flattens the rising intonation of a question rather than
     * silencing anything, which is almost never what someone wants.
     */
    val SKIPPABLE_SYMBOLS: List<String> = listOf(
        "*", "_", "~", "^", "|", "`", "#", "•", "◆", "★", "☆",
        "※", "▪", "▫", "■", "□", "●", "○", "→", "←", "⇒",
        "\uFFFD"   // replacement char: mojibake from bad encoding upstream
    )

    data class Rule(val word: String, val replacement: String)

    /** A rule targeting punctuation/symbols rather than a word. */
    fun isSymbolRule(word: String): Boolean =
        word.isNotEmpty() && word.none { it.isLetterOrDigit() }

    class Compiled internal constructor(
        private val pattern: Regex?,
        private val lookup: Map<String, String>,
        private val hasRemovals: Boolean
    ) {
        fun apply(text: String): String {
            val regex = pattern ?: return text
            if (text.isEmpty()) return text

            val replaced = regex.replace(text) { match ->
                // Lookarounds are zero-width, so the match is exactly the rule
                // word. Lowercased because matching is case-insensitive.
                lookup[match.value.lowercase()] ?: match.value
            }

            // Only tidy up when something was actually deleted. A dictionary of
            // pure substitutions should leave spacing exactly as the author
            // wrote it.
            return if (hasRemovals) tidy(replaced) else replaced
        }

        /**
         * Removing a symbol leaves debris: "Hello * world" becomes "Hello
         * world" with a double space, and "Hello *, world" leaves a space
         * before the comma. Both make TTS pause oddly.
         */
        private fun tidy(text: String): String = text
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("[ \\t]+([,.;:!?])"), "$1")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun compile(rules: List<Rule>): Compiled {
        val usable = rules.filter { it.word.isNotBlank() }
        if (usable.isEmpty()) return Compiled(null, emptyMap(), false)

        // Longest word first so "Li Wei" wins over "Li". Regex alternation is
        // first-match-wins, not longest-match-wins, so ordering is the only
        // thing that makes multi-word rules reachable at all.
        val ordered = usable.sortedByDescending { it.word.length }

        val branches = ordered.map { rule ->
            val escaped = Regex.escape(rule.word)
            if (isSymbolRule(rule.word)) {
                // No boundaries: symbols sit flush against words.
                escaped
            } else {
                // Boundary = string edge, whitespace, or any Unicode
                // punctuation. \p{Punct} alone is ASCII-only, which missed
                // curly quotes and guillemets around names.
                "(?<=^|[\\s\\p{Punct}\\p{IsPunctuation}])$escaped" +
                        "(?=$|[\\s\\p{Punct}\\p{IsPunctuation}])"
            }
        }

        val pattern = runCatching {
            Regex(branches.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE)
        }.getOrNull() ?: return Compiled(null, emptyMap(), false)

        // Later duplicates lose, matching the longest-first ordering above.
        val lookup = LinkedHashMap<String, String>()
        for (rule in ordered) lookup.putIfAbsent(rule.word.lowercase(), rule.replacement)

        return Compiled(pattern, lookup, usable.any { it.replacement.isEmpty() })
    }
}