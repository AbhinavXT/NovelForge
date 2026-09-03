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
     * What one chapter's scan learned about a name.
     *
     * @param occurrences times the name was counted in this chapter.
     * @param speechHits times it sat next to a speech or gesture verb
     *   ("Xiulan said", "asked Chen"). Places and factions don't talk,
     *   so this is the one classification signal that has to be
     *   gathered while the surrounding words are still in hand —
     *   everything else in [classify] reads off the name itself.
     */
    data class NameStats(val occurrences: Int, val speechHits: Int)

    /**
     * Extracts candidate names from one chapter's paragraphs.
     */
    fun extract(paragraphs: List<String>): Map<String, NameStats> {
        val strong = HashMap<String, Int>()
        val weak = HashMap<String, Int>()
        val speech = HashMap<String, Int>()

        for (para in paragraphs) {
            for (sentence in SENTENCE_SPLIT.split(para)) {
                val tokens = WORD_REGEX.findAll(sentence).map { it.value }.toList()
                // Normalize once per token: null means "cannot be part of a name".
                val cands = tokens.map { candidate(it) }
                var i = 0
                var firstWord = true
                while (i < tokens.size) {
                    if (cands[i] == null) {
                        i++
                        firstWord = false
                        continue
                    }
                    // Measure the FULL capitalized run before taking a name
                    // from it. Taking the first MAX_NAME_TOKENS and then
                    // resuming at the end of the run — not at the end of the
                    // name — is what stops a 4+ token run from shedding its
                    // tail as a bogus standalone entry.
                    var runEnd = i
                    while (runEnd < tokens.size && cands[runEnd] != null) runEnd++

                    val take = minOf(MAX_NAME_TOKENS, runEnd - i)
                    val parts = (i until i + take).map { cands[it]!! }
                    val name = parts.joinToString(" ")
                    val multi = take > 1

                    // A phrase with no proper noun in it is a form of
                    // address, not a name — "Sect Master", "Young Master",
                    // "Old Man". Add one real token and it becomes a name
                    // again ("Sect Master Zhao"), which is why this tests
                    // the whole phrase rather than just the head.
                    //
                    // The title requirement is what keeps genre compounds
                    // like "Demon King" and "Spirit Realm" — all-generic
                    // but genuinely entries — out of the reject bucket.
                    // For a single token it can't change the outcome, so
                    // the old standalone rule is preserved exactly.
                    val lower = parts.map { it.lowercase() }
                    val genericPhrase = lower.all { it in GENERIC_STANDALONE } &&
                            (!multi || lower.any { it in TITLE_WORDS })

                    if (!genericPhrase) {
                        if (firstWord && !multi) {
                            weak[name] = (weak[name] ?: 0) + 1
                        } else {
                            strong[name] = (strong[name] ?: 0) + 1
                        }
                        // Look one token either side of the run. Both
                        // orders occur constantly in translated prose:
                        // "Xiulan said" and "said Xiulan".
                        val before = tokens.getOrNull(i - 1)?.lowercase()
                        val after = tokens.getOrNull(runEnd)?.lowercase()
                        if (before in SPEECH_VERBS || after in SPEECH_VERBS) {
                            speech[name] = (speech[name] ?: 0) + 1
                        }
                    }
                    i = runEnd
                    firstWord = false
                }
            }
        }

        // Weak (sentence-initial) hits only count for names that
        // earned strong evidence in this chapter.
        val counts = HashMap<String, Int>(strong)
        for ((name, count) in weak) {
            if (name in strong) counts[name] = counts.getValue(name) + count
        }
        return counts.mapValues { (name, c) -> NameStats(c, speech[name] ?: 0) }
    }

    /** Longest phrase, in tokens, that merges into a single entry. */
    private const val MAX_NAME_TOKENS = 3

    /**
     * Normalizes a raw token to the form stored in the codex, or null
     * if it can't be part of a name: too short, not capitalized,
     * ALL-CAPS shouting, or a stopword.
     *
     * Normalization strips a trailing possessive so `Xiulan's` and
     * `Xiulan` are one entry rather than two — splitting the count
     * used to push real characters under CodexEngine's MIN_OCCURRENCES
     * floor and drop them from the codex entirely. Only a TRAILING
     * apostrophe is touched, so `D'Artagnan` survives intact.
     */
    private fun candidate(tok: String): String? {
        // Contractions are verb forms wearing a capital letter. "I'm",
        // "Don't", "You'll" all survived every other test here — the
        // apostrophe kept them out of the stopword list and the
        // capital carried them into the codex as characters.
        if (CONTRACTION_SUFFIX.containsMatchIn(tok)) return null
        val t = tok.replace(POSSESSIVE_SUFFIX, "").trimEnd('-')
        if (t.length <= 1) return null
        if (!t[0].isUpperCase()) return null
        if (t == t.uppercase()) return null          // ALL-CAPS shouting
        if (t.lowercase() in STOPWORDS) return null
        return t
    }

    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n")

    /**
     * Unicode letters, not just ASCII. `[A-Za-z]` meant a name opening
     * with an accented capital (`Éowyn`, tone-marked pinyin) could not
     * start a token at all, and the lowercase remainder was discarded —
     * every such name was invisible to the codex. \p{M} carries
     * combining marks for decomposed forms.
     */
    private val WORD_REGEX = Regex("\\p{L}[\\p{L}\\p{M}'’\\-]*")

    /** Trailing `'s`, `’s`, or a bare possessive apostrophe (`the Zhaos'`). */
    private val POSSESSIVE_SUFFIX = Regex("['’]s$|['’]$", RegexOption.IGNORE_CASE)

    /**
     * Contraction tails. Matched anywhere so `n't` is caught mid-token,
     * and deliberately NOT including `'s` — that one is the possessive
     * handled above, and stripping it is what keeps `Xiulan's` and
     * `Xiulan` a single entry.
     */
    private val CONTRACTION_SUFFIX =
        Regex("n['’]t$|['’](m|ll|d|ve|re)$", RegexOption.IGNORE_CASE)

    /**
     * Scanlation and site furniture. These ride along in chapter text
     * — "Author's Note", "Translator: …", "Chapter 41", Patreon
     * plugs — and every one of them is capitalized, recurring, and
     * completely uninteresting. Stopword-level rather than generic:
     * they should break a run, not sit inside a name.
     */
    private val BOILERPLATE: Set<String> = """
        author translator translation translated editor proofreader proofread raw raws
        note notes chapter chapters volume part prologue epilogue foreword afterword arc
        patreon discord kofi paypal sponsor sponsored support donate advance advanced
        index contents next previous prev continue release released schedule
        comment comments review reviews rating ratings bonus extra teaser
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * Spelled-out numbers. "Chapter" now breaks the run, which would
     * otherwise leave "Twelve" standing alone as a codex entry.
     */
    private val NUMBER_WORDS: Set<String> = """
        one two three four five six seven eight nine ten eleven twelve thirteen fourteen
        fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty
        seventy eighty ninety hundred thousand million
        first second third fourth fifth sixth seventh eighth ninth tenth last final
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * Speech and gesture verbs. Adjacency to one of these is the
     * strongest cheap signal that a name belongs to someone who can
     * act — a mountain never replies.
     */
    private val SPEECH_VERBS: Set<String> = """
        said says say asked asks ask replied replies reply answered answers answer
        shouted shouts yelled cried called calls muttered murmured whispered
        laughed smiled smiles grinned frowned scowled sighed nodded shook
        thought thinks wondered realized snorted chuckled snapped growled roared
        exclaimed continued added interrupted responded retorted stammered
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /** Head nouns that make a phrase an organisation rather than a person. */
    private val FACTION_HEADS: Set<String> = """
        sect clan family guild alliance association order tribe league union
        empire kingdom dynasty court house school academy institute society
        legion army corps division squad team faction pavilion department
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /** Head nouns that make a phrase somewhere you can stand. */
    private val PLACE_HEADS: Set<String> = """
        city town village capital province region realm continent world land domain
        mountain mountains peak valley forest woods lake river sea ocean island
        desert plains plain cave cavern gate gates bridge road street market square
        palace hall tower temple shrine monastery manor estate garden courtyard
        prison tomb ruins battlefield arena plaza inn tavern
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * What kind of thing an entry is, for grouping the codex list.
     * UNKNOWN is a real answer, not a failure — a bare two-syllable
     * name with no title, no speech line, and no head noun genuinely
     * doesn't say what it is, and guessing PERSON there would put
     * every unlabelled place in with the cast.
     */
    enum class CodexType(val label: String) {
        PERSON("People"),
        PLACE("Places"),
        FACTION("Factions"),
        UNKNOWN("Other")
    }

    /**
     * Classifies an entry from the name plus accumulated speech
     * evidence. Pure — no state, no scan — so it runs at read time
     * and improvements to the rules take effect without a rescan.
     *
     * Head noun first: it's the highest-precision signal available
     * and it beats a title word, because "Zhao Family" is an
     * organisation even though `family` is also a form of address.
     */
    fun classify(name: String, speechHits: Int): CodexType {
        val parts = name.split(' ')
        val head = parts.last().lowercase()
        if (head in FACTION_HEADS) return CodexType.FACTION
        if (head in PLACE_HEADS) return CodexType.PLACE
        if (parts.any { it.lowercase() in TITLE_WORDS }) return CodexType.PERSON
        if (speechHits >= 2) return CodexType.PERSON
        return CodexType.UNKNOWN
    }


    private val BASE_STOPWORDS: Set<String> = """
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
     * Forms of address and relational roles. These never name anyone,
     * alone or stacked: "Young Master", "Elder Sister", "Old Man",
     * "Sect Master", "City Lord" are all what someone is CALLED.
     */
    private val TITLE_WORDS: Set<String> = """
        elder senior junior brother sister uncle aunt master young old man woman
        sir madam miss mister lord lady
        boss chief captain general commander doctor teacher father mother grandpa grandma kid boy girl
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * Domain vocabulary. Can't stand alone as an entry, but unlike a
     * title these do combine into real proper nouns — "Demon King",
     * "Spirit Realm", "Sword God" are entries a reader would look up.
     */
    private val COMMON_NOUNS: Set<String> = """
        sect city clan family mountain heaven earth god demon king queen prince princess emperor empress
        sword blade art dao qi spirit soul realm stage peak
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /**
     * Generic capitalized words that shouldn't stand alone as codex
     * entries but are legitimate INSIDE multi-word names
     * ("Elder Chen", "Young Master Wei", "Azure Cloud Sect").
     */
    private val GENERIC_STANDALONE: Set<String> = TITLE_WORDS + COMMON_NOUNS

    /**
     * Composed last on purpose: properties in an `object` initialize
     * in declaration order, so this has to sit below every set it
     * unions or the components are still null when it runs.
     */
    private val STOPWORDS: Set<String> = BASE_STOPWORDS + BOILERPLATE + NUMBER_WORDS
}