package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.PronunciationRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All assertions verified against the real implementation.
 */
class PronunciationRulesTest {

    private fun apply(rules: List<Rule>, text: String) =
        PronunciationRules.compile(rules).apply(text)

    // ── Skipping symbols ─────────────────────────────────────────────────

    @Test
    fun `symbol flush against letters is removed`() {
        // The whole point of symbol rules. A word-boundary pattern cannot
        // match here — the asterisk has a letter on one side.
        assertEquals(
            "She said emphasis loudly.",
            apply(listOf(Rule("*", "")), "She said *emphasis* loudly.")
        )
    }

    @Test
    fun `several symbol rules apply together`() {
        assertEquals(
            "Hello world",
            apply(listOf(Rule("*", ""), Rule("_", ""), Rule("~", "")), "Hello *_~world")
        )
    }

    @Test
    fun `whitespace left behind by a removal is tidied`() {
        assertEquals("Hello world", apply(listOf(Rule("*", "")), "Hello * world"))
        assertEquals("Hello, world", apply(listOf(Rule("*", "")), "Hello *, world"))
    }

    @Test
    fun `scene break line disappears cleanly`() {
        assertEquals(
            "End of scene.\n\nNew scene begins.",
            apply(listOf(Rule("*", "")), "End of scene.\n***\nNew scene begins.")
        )
    }

    @Test
    fun `unicode replacement character from bad encoding is removed`() {
        assertEquals("naive text", apply(listOf(Rule("\uFFFD", "")), "nai\uFFFDve text"))
    }

    // ── Skipping words ───────────────────────────────────────────────────

    @Test
    fun `a word can be silenced`() {
        assertEquals(
            "The chapter begins.",
            apply(listOf(Rule("Advertisement", "")), "Advertisement The chapter begins.")
        )
    }

    @Test
    fun `silencing a word still respects word boundaries`() {
        // Unlike symbols, word rules must not fire inside a longer word.
        assertEquals("Lisa went home.", apply(listOf(Rule("Li", "")), "Lisa went home."))
    }

    @Test
    fun `pure substitution leaves spacing untouched`() {
        // Tidying only runs when something was actually deleted, so a
        // dictionary of substitutions cannot reformat the author's text.
        assertEquals(
            "Hello  shoo-lan",
            apply(listOf(Rule("Xiulan", "shoo-lan")), "Hello  Xiulan")
        )
    }

    // ── Substitution ─────────────────────────────────────────────────────

    @Test
    fun `basic substitution is case insensitive`() {
        assertEquals("shoo-lan ran.", apply(listOf(Rule("Xiulan", "shoo-lan")), "Xiulan ran."))
        assertEquals("shoo-lan ran.", apply(listOf(Rule("Xiulan", "shoo-lan")), "XIULAN ran."))
    }

    @Test
    fun `accented names match`() {
        assertEquals("nay-lith spoke.", apply(listOf(Rule("Naëlith", "nay-lith")), "Naëlith spoke."))
    }

    @Test
    fun `curly quotes count as word boundaries`() {
        // \p{Punct} alone is ASCII-only and missed these.
        assertEquals(
            "\u201Cshoo-lan\u201D",
            apply(listOf(Rule("Xiulan", "shoo-lan")), "\u201CXiulan\u201D")
        )
    }

    @Test
    fun `no match inside a longer word`() {
        assertEquals("Xiulanning", apply(listOf(Rule("Xiulan", "shoo-lan")), "Xiulanning"))
    }

    // ── Single-pass guarantees ───────────────────────────────────────────

    @Test
    fun `a replacement is not re-matched by another rule`() {
        // REGRESSION. Rules used to run sequentially over the accumulated
        // result, so Li -> Lee -> Leigh cascaded and "Li" came out "Leigh".
        assertEquals("Lee", apply(listOf(Rule("Li", "Lee"), Rule("Lee", "Leigh")), "Li"))
    }

    @Test
    fun `longer rule wins over a prefix rule`() {
        // Regex alternation is first-match-wins, so rules are ordered longest
        // first — otherwise "Li" would shadow "Li Wei" permanently.
        assertEquals(
            "LEE-WAY",
            apply(listOf(Rule("Li", "Lee"), Rule("Li Wei", "LEE-WAY")), "Li Wei")
        )
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun `empty rule list is a passthrough`() {
        assertEquals("unchanged  text ", apply(emptyList(), "unchanged  text "))
    }

    @Test
    fun `blank word is ignored`() {
        assertEquals("text", apply(listOf(Rule("  ", "x")), "text"))
    }

    @Test
    fun `regex metacharacters in a word are treated literally`() {
        assertEquals("ok", apply(listOf(Rule("(a+b)", "ok")), "(a+b)"))
    }

    @Test
    fun `symbol rules are detected by absence of letters and digits`() {
        assertTrue(PronunciationRules.isSymbolRule("*"))
        assertTrue(PronunciationRules.isSymbolRule("###"))
        assertFalse(PronunciationRules.isSymbolRule("Li"))
    }

    // ── The preset ───────────────────────────────────────────────────────

    @Test
    fun `preset excludes punctuation that carries prosody`() {
        // Removing "?" does not silence anything — it flattens the rising
        // intonation of a question. Same for ! . , ; : and quotes.
        val prosody = listOf("?", "!", ".", ",", ";", ":", "\"", "'")
        assertFalse(PronunciationRules.SKIPPABLE_SYMBOLS.any { it in prosody })
    }

    @Test
    fun `preset strips decoration but keeps sentence punctuation`() {
        val preset = PronunciationRules.SKIPPABLE_SYMBOLS.map { Rule(it, "") }
        assertEquals("Are you there? Yes.", apply(preset, "Are you there? *Yes.*"))
    }
}