package com.abhinavxt.novelforge.data.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * NameExtractor is pure heuristics, which means its behaviour is defined by
 * examples rather than by a spec. These tests ARE the spec.
 *
 * The passing tests below were all verified against the real implementation.
 * The @Ignore'd ones at the bottom are confirmed defects written as executable
 * specifications -- delete the annotation once each is fixed.
 */
class NameExtractorTest {

    private fun extract(vararg paragraphs: String) =
        NameExtractor.extract(paragraphs.toList())

    // ── Core heuristics ──────────────────────────────────────────────────

    @Test
    fun `mid-sentence capitalised token is strong evidence`() {
        assertEquals(mapOf("Xiulan" to 1), extract("The hero Xiulan appeared."))
    }

    @Test
    fun `sentence-initial word alone is not enough`() {
        // "Suddenly" and "However" are sentence-initial only, so they never
        // earn a codex entry. This is the rule that keeps the codex readable.
        assertEquals(emptyMap<String, Int>(), extract("Suddenly the door opened. However nothing moved."))
    }

    @Test
    fun `sentence-initial hits count once the name has strong evidence`() {
        // "Xiulan" leads sentence one (weak) and appears mid-sentence in
        // sentence two (strong) -- the weak hit is then redeemed, total 2.
        assertEquals(mapOf("Xiulan" to 2), extract("Xiulan ran. The elder saw Xiulan."))
    }

    @Test
    fun `consecutive capitalised tokens merge into one name`() {
        assertEquals(mapOf("Elder Chen" to 1), extract("Then Elder Chen spoke."))
    }

    @Test
    fun `generic title words cannot stand alone as entries`() {
        assertEquals(emptyMap<String, Int>(), extract("The elder spoke. Elder nodded."))
    }

    @Test
    fun `all caps shouting is excluded`() {
        assertEquals(emptyMap<String, Int>(), extract("He yelled STOP at them."))
    }

    @Test
    fun `single letters are excluded`() {
        assertEquals(emptyMap<String, Int>(), extract("A man walked by."))
    }

    @Test
    fun `hyphenated names are kept whole`() {
        assertEquals(mapOf("Li-Wei" to 2), extract("The warrior Li-Wei stood. Li-Wei fought."))
    }

    @Test
    fun `name opening quoted dialogue is redeemed by a later strong hit`() {
        assertEquals(mapOf("Xiulan" to 2), extract("\"Xiulan,\" he said. He saw Xiulan again."))
    }

    @Test
    fun `empty input yields no names`() {
        assertEquals(emptyMap<String, Int>(), extract())
        assertEquals(emptyMap<String, Int>(), extract(""))
    }

    // ── Confirmed defects ────────────────────────────────────────────────
    // Each was reproduced against the real implementation. They are @Ignore'd
    // so the suite stays green; remove the annotation when fixing.

    @Test
    @Ignore("KNOWN BUG: possessives become separate codex entries")
    fun `possessive form is not a separate name`() {
        // Actual:   {Xiulan's=1, Xiulan=2}
        // Expected: {Xiulan=3}
        // WORD_REGEX is [A-Za-z][A-Za-z'’\-]* so the trailing 's is captured as
        // part of the token. The codex therefore shows "Xiulan" and "Xiulan's"
        // as two characters, splitting the occurrence count between them --
        // which can push a genuine name below MIN_OCCURRENCES and hide it.
        // Fix: strip a trailing 's / ’s before recording the name.
        val result = extract("He took Xiulan's sword. Xiulan smiled at Xiulan.")
        assertFalse("possessive leaked in as its own entry", result.containsKey("Xiulan's"))
        assertEquals(3, result["Xiulan"])
    }

    @Test
    @Ignore("KNOWN BUG: non-ASCII names are dropped entirely")
    fun `accented names are extracted`() {
        // Actual:   {}
        // Expected: {Éowyn=2}
        // WORD_REGEX starts with [A-Za-z], so 'É' fails to open a token and the
        // remaining "owyn" is lowercase and ignored. Every name with a diacritic
        // is invisible to the codex -- including pinyin with tone marks, which
        // matters given the translated-webnovel use case.
        // Fix: use \p{L} instead of A-Za-z, and isUpperCase() already handles
        // the capitalisation check for non-ASCII correctly.
        assertEquals(mapOf("Éowyn" to 2), extract("Then Éowyn drew her blade. Éowyn stood."))
    }

    @Test
    @Ignore("KNOWN BUG: names longer than 3 tokens leave a junk tail entry")
    fun `four token name does not produce a stray fragment`() {
        // Actual:   {Old Man Zhao=1, Wei=1}
        // Expected: no bare "Wei" entry.
        // The greedy merge caps a phrase at 3 tokens then resumes scanning from
        // the 4th, so the leftover token becomes its own entry. "Wei" is noise
        // that appears in the codex as if it were a distinct character.
        // Fix: after capping, skip the rest of the capitalised run instead of
        // restarting the scan inside it.
        val result = extract("Then Old Man Zhao Wei arrived.")
        assertTrue(result.containsKey("Old Man Zhao"))
        assertFalse("stray tail token leaked in", result.containsKey("Wei"))
    }
}