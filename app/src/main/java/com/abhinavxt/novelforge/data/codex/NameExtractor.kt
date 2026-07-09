package com.abhinavxt.novelforge.data.codex

/**
 * Heuristic proper-noun extractor for the character codex. No ML —
 * capitalization patterns carry almost all the signal in translated
 * webnovel prose, and the rules below were validated against
 * MTL-style text before porting:
 *
 *  - A capitalized token mid-sentence is STRONG evidence of a name.
 *  - A capitalized token at sentence start is WEAK: it only counts
 *    if the same name also has strong evidence somewhere in the
 *    chapter ("Suddenly" never survives; "Xiulan looked…" does,
 *    because she also appears mid-sentence).
 *  - Consecutive capitalized tokens merge into one name, longest
 *    match wins ("Li Wei", "Elder Chen", "Old Man Zhao" — max 3).
 *  - Generic title words ("Elder", "Sect", "Master"…) can live
 *    INSIDE a phrase but never stand alone as an entry.
 *  - Stopwords, pronouns, and ALL-CAPS shouting are excluded.
 *
 * Places and factions ("Azure Cloud Sect") intentionally pass — a
 * codex covers who AND what.
 */
object NameExtractor {

    /**
     * Extracts candidate names from one chapter's paragraphs.
     * Returns name → occurrence count within this chapter.
     */
    fun extract(paragraphs: List<String>): Map<String, Int> {
        val strong = HashMap<String, Int>()
        val weak = HashMap<String, Int>()

        for (para in paragraphs) {
            for (sentence in SENTENCE_SPLIT.split(para)) {
                val tokens = WORD_REGEX.findAll(sentence).map { it.value }.toList()
                var i = 0
                var firstWord = true
                while (i < tokens.size) {
                    val tok = tokens[i]
                    if (isNameCap(tok) && tok.lowercase() !in STOPWORDS) {
                        // Greedy multi-word merge, up to 3 tokens.
                        var j = i + 1
                        val phrase = mutableListOf(tok)
                        while (j < tokens.size && phrase.size < 3 &&
                            isNameCap(tokens[j]) && tokens[j].lowercase() !in STOPWORDS
                        ) {
                            phrase += tokens[j]
                            j++
                        }
                        val name = phrase.joinToString(" ")
                        val multi = phrase.size > 1
                        val standaloneGeneric = !multi && tok.lowercase() in GENERIC_STANDALONE

                        if (!standaloneGeneric) {
                            if (firstWord && !multi) {
                                weak[name] = (weak[name] ?: 0) + 1
                            } else {
                                strong[name] = (strong[name] ?: 0) + 1
                            }
                        }
                        i = j
                        firstWord = false
                    } else {
                        i++
                        firstWord = false
                    }
                }
            }
        }

        // Weak (sentence-initial) hits only count for names that
        // earned strong evidence in this chapter.
        val out = HashMap<String, Int>(strong)
        for ((name, count) in weak) {
            if (name in strong) out[name] = out.getValue(name) + count
        }
        return out
    }

    /** Capitalized, longer than one char, and not ALL-CAPS shouting. */
    private fun isNameCap(tok: String): Boolean =
        tok.length > 1 && tok[0].isUpperCase() && tok != tok.uppercase()

    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n")
    private val WORD_REGEX = Regex("[A-Za-z][A-Za-z'’\\-]*")

    private val STOPWORDS: Set<String> = """
        the a an and or but if then else when while for nor so yet as of in on at by to from with without
        into onto over under above below before after during between among through across behind beyond near
        he she it they we you i him her them us me his hers its their our your my mine yours theirs ours
        this that these those there here who whom whose which what why how where
        is are was were be been being am do does did done have has had having will would shall should can could may might must
        not no yes all any both each few more most other some such only own same than too very just even still also again once
        oh ah hey hmm huh wow damn hello okay ok everyone everything something nothing anyone someone nobody somebody
        suddenly however meanwhile perhaps although though because since until unless
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * Generic capitalized words that shouldn't stand alone as codex
     * entries but are legitimate INSIDE multi-word names
     * ("Elder Chen", "Young Master Wei", "Sect Master").
     */
    private val GENERIC_STANDALONE: Set<String> = """
        elder senior junior brother sister uncle aunt master young old man woman
        sect city clan family mountain heaven earth god demon king queen prince princess emperor empress
        chapter sword blade art dao qi spirit soul realm stage peak sir madam miss mister lord lady
        boss chief captain general commander doctor teacher father mother grandpa grandma kid boy girl
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
}
