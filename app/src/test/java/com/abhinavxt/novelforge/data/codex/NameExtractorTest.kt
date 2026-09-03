package com.abhinavxt.novelforge.data.codex

import org.junit.Assert.assertEquals
import org.junit.Test
import com.abhinavxt.novelforge.data.codex.NameExtractor.CodexType

private fun counts(vararg paragraphs: String): Map<String, Int> =
    NameExtractor.extract(paragraphs.toList()).mapValues { it.value.occurrences }

/**
 * Replacements for the three `@Ignore`d tests in NameExtractorTest.
 * Each asserted the correct behaviour and failed against the old
 * against the old implementation. Drop these in place of the
 * ignored ones and delete the `@Ignore` import if nothing else uses it.
 *
 * The regression guards below pin the behaviour that must NOT change
 * as a result of the fixes — they were green before and after.
 */
class NameExtractorFixesTest {

    // ── Was @Ignore: possessives split the count ──────────────────

    @Test
    fun `possessive folds into the base name`() {
        assertEquals(
            mapOf("Xiulan" to 3),
            counts("He took Xiulan's sword. Xiulan smiled at Xiulan.")
        )
    }

    @Test
    fun `plural possessive folds into the base name`() {
        assertEquals(
            mapOf("Zhaos" to 3),
            counts("He razed the Zhaos' estate. He hunted the Zhaos and the Zhaos.")
        )
    }

    @Test
    fun `apostrophe inside a name is preserved`() {
        assertEquals(
            mapOf("D'Artagnan" to 3),
            counts("He met D'Artagnan. She saw D'Artagnan beside D'Artagnan.")
        )
    }

    // ── Was @Ignore: non-ASCII names invisible ────────────────────

    @Test
    fun `accented name is captured whole`() {
        assertEquals(
            mapOf("\u00c9owyn" to 3),
            counts("Then \u00c9owyn drew her blade. He saw \u00c9owyn beside \u00c9owyn.")
        )
    }

    @Test
    fun `tone-marked pinyin is captured whole`() {
        assertEquals(
            mapOf("L\u01d0 W\u011bi" to 3),
            counts("He bowed to L\u01d0 W\u011bi. She saw L\u01d0 W\u011bi with L\u01d0 W\u011bi.")
        )
    }

    // ── Was @Ignore: 4+ token names leave a junk tail ─────────────

    @Test
    fun `four token run does not shed a tail entry`() {
        assertEquals(
            mapOf("Old Man Zhao" to 2),
            counts("Then Old Man Zhao Wei arrived. He greeted Old Man Zhao Wei again.")
        )
    }

    @Test
    fun `five token run does not shed a tail entry`() {
        assertEquals(
            mapOf("Young Master Wei" to 1),
            counts("He saw Young Master Wei Chen Long there.")
        )
    }

    // ── Regression guards ─────────────────────────────────────────

    @Test
    fun `all caps shouting is excluded`() {
        assertEquals(emptyMap<String, Int>(), counts("SHE WAS FURIOUS AND LOUD."))
    }

    @Test
    fun `sentence initial adverb is excluded`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("Suddenly the bell rang. Suddenly it stopped.")
        )
    }

    @Test
    fun `generic word alone is not an entry`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("The Elder nodded. He saw the Master wait.")
        )
    }

    @Test
    fun `name with only sentence initial evidence is dropped`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("Bartholomew arrived. Bartholomew left.")
        )
    }

    @Test
    fun `multi word phrase still merges`() {
        assertEquals(
            mapOf("Azure Cloud Sect" to 2),
            counts("She entered the Azure Cloud Sect. He left the Azure Cloud Sect.")
        )
    }

    // ── Generic phrases: forms of address are not names ───────────

    @Test
    fun `all generic phrase with a title is rejected`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("The Sect Master nodded at the Sect Master.")
        )
    }

    @Test
    fun `stacked honorific is rejected`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("He bowed to the Young Master. The Young Master ignored him.")
        )
    }

    @Test
    fun `relational title is rejected`() {
        assertEquals(
            emptyMap<String, Int>(),
            counts("She greeted her Elder Sister. Her Elder Sister smiled.")
        )
    }

    @Test
    fun `domain compound without a title is kept`() {
        assertEquals(
            mapOf("Demon King" to 2),
            counts("He fought the Demon King. The Demon King laughed.")
        )
    }

    @Test
    fun `second domain compound without a title is kept`() {
        assertEquals(
            mapOf("Spirit Realm" to 2),
            counts("She entered the Spirit Realm. The Spirit Realm was vast.")
        )
    }

    @Test
    fun `a proper noun rescues a title phrase`() {
        assertEquals(
            mapOf("Sect Master Zhao" to 2),
            counts("He met Sect Master Zhao. Sect Master Zhao bowed.")
        )
    }

    @Test
    fun `single domain noun is still rejected alone`() {
        assertEquals(emptyMap<String, Int>(), counts("He killed the Demon. The Demon screamed."))
    }

    // ── Noise vocabulary ──────────────────────────────────────────

    @Test
    fun `contractions are not names`() {
        assertEquals(emptyMap<String, Int>(), counts("I'm sure of it. I'm certain. I'm ready."))
        assertEquals(emptyMap<String, Int>(), counts("Don't move. Don't speak. Don't run."))
        assertEquals(emptyMap<String, Int>(), counts("You'll see. You'll learn. You'll regret it."))
    }

    @Test
    fun `apostrophe name survives contraction filtering`() {
        assertEquals(
            mapOf("O'Brien" to 3),
            counts("He met O'Brien. She saw O'Brien with O'Brien.")
        )
    }

    @Test
    fun `scanlation boilerplate is not a name`() {
        assertEquals(emptyMap<String, Int>(), counts("Author's Note follows. Author's Note again. Author's Note."))
        assertEquals(emptyMap<String, Int>(), counts("A Translator worked here. The Translator left. Translator."))
    }

    @Test
    fun `chapter headings do not leave a number word behind`() {
        assertEquals(emptyMap<String, Int>(), counts("Chapter Twelve begins. Chapter Twelve ends. Chapter Twelve."))
    }

    // ── Classification ────────────────────────────────────────────

    @Test
    fun `faction head noun wins`() {
        assertEquals(CodexType.FACTION, NameExtractor.classify("Azure Cloud Sect", 0))
        assertEquals(CodexType.FACTION, NameExtractor.classify("Zhao Family", 0))
    }

    @Test
    fun `place head noun wins`() {
        assertEquals(CodexType.PLACE, NameExtractor.classify("Jade Peak", 0))
        assertEquals(CodexType.PLACE, NameExtractor.classify("Cloud City", 0))
    }

    @Test
    fun `title word marks a person`() {
        assertEquals(CodexType.PERSON, NameExtractor.classify("Elder Chen", 0))
        assertEquals(CodexType.PERSON, NameExtractor.classify("Young Master Wei", 0))
    }

    @Test
    fun `speech attribution marks a person`() {
        val stats = NameExtractor.extract(
            listOf("\"You are late,\" said Xiulan. Xiulan smiled. He greeted Xiulan and Xiulan replied.")
        )
        val xiulan = requireNotNull(stats["Xiulan"])
        assertEquals(CodexType.PERSON, NameExtractor.classify("Xiulan", xiulan.speechHits))
    }

    @Test
    fun `bare name with no evidence stays unknown`() {
        assertEquals(CodexType.UNKNOWN, NameExtractor.classify("Bartholomew", 0))
    }
}