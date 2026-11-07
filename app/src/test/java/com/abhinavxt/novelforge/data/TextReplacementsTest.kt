package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.PronunciationRules.Rule
import com.abhinavxt.novelforge.data.database.TextReplacementEntity
import com.abhinavxt.novelforge.data.epub.Footnote
import com.abhinavxt.novelforge.data.epub.InlineStyle
import com.abhinavxt.novelforge.data.epub.StyleSpan
import com.abhinavxt.novelforge.ui.components.ParagraphFormat
import com.abhinavxt.novelforge.ui.components.mapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacementsTest {

    private fun shown(text: String, vararg rules: Pair<String, String>) =
        TextReplacements.apply(PronunciationRules.compile(rules.map { Rule(it.first, it.second) }), text)

    // ── What is shown ────────────────────────────────────────────────

    @Test
    fun `whole words are replaced, not parts of words`() {
        assertEquals("Lee met Lisa.", shown("Li met Lisa.", "Li" to "Lee").text)
    }

    @Test
    fun `no rules or no matches leave the text and report no change`() {
        val none = shown("Plain text.")
        assertEquals("Plain text.", none.text)
        assertFalse(none.isChanged)
        assertFalse(shown("Plain text.", "Li" to "Lee").isChanged)
    }

    @Test
    fun `replacement follows the case of what it replaces`() {
        assertEquals("She ran. she sat. SHE won.", shown("He ran. he sat. HE won.", "he" to "she").text)
        // Typed capitals are kept as typed.
        assertEquals("Ye Fan left.", shown("ye fan left.", "ye fan" to "Ye Fan").text)
    }

    @Test
    fun `one pass, so a replacement is never replaced again`() {
        assertEquals("Lee and Leigh", shown("Li and Lee", "Li" to "Lee", "Lee" to "Leigh").text)
    }

    @Test
    fun `removal takes the spaces on one side with it`() {
        assertEquals("Hello world", shown("Hello * world", "*" to "").text)
        assertEquals("Hello, world", shown("Hello *, world", "*" to "").text)
        assertEquals("Hello", shown("Hello *", "*" to "").text)
        assertEquals("Hello", shown("* Hello", "*" to "").text)
        assertEquals("emphasis", shown("*emphasis*", "*" to "").text)
        assertEquals("", shown("Read at example site", "Read at example site" to "").text)
    }

    @Test
    fun `removal right after a replacement keeps the replacement`() {
        assertEquals("Lee x", shown("Li* x", "Li" to "Lee", "*" to "").text)
    }

    // ── Offsets ──────────────────────────────────────────────────────

    @Test
    fun `offsets after a replacement shift by its change in length`() {
        // "Li sat down" -> "Lee sat down": "sat" moves from 3 to 4.
        val m = shown("Li sat down", "Li" to "Lee")
        assertEquals(4, m.toDisplay(3, isEnd = false))
        assertEquals(7, m.toDisplay(6, isEnd = true))
        assertEquals(3, m.toOriginal(4, isEnd = false))
        assertEquals(11, m.toOriginal(12, isEnd = true))
    }

    @Test
    fun `a range touching a replacement covers all of it`() {
        // "Ye Fan" (0..6) -> "Yu Van Long" (0..11)
        val m = shown("Ye Fan smiled", "Ye Fan" to "Yu Van Long")
        assertEquals(0 until 11, m.rangeToDisplay(3, 6)) // just "Fan"
        assertEquals(0 until 11, m.rangeToDisplay(0, 2)) // just "Ye"
        assertEquals(12 until 18, m.rangeToDisplay(7, 13)) // "smiled"
    }

    @Test
    fun `a range over removed text is gone, one beside it survives`() {
        // "A watermark here. Text." with "watermark here." removed
        val m = shown("A watermark here. Text.", "watermark here." to "")
        assertEquals("A Text.", m.text)
        assertNull(m.rangeToDisplay(2, 17))
        assertEquals(2 until 7, m.rangeToDisplay(18, 23))
    }

    @Test
    fun `a selection on shown text maps back to the stored text`() {
        // Highlighting "Lee sat" in "Lee sat down" stores "Li sat".
        val m = shown("Li sat down", "Li" to "Lee")
        val start = m.toOriginal(0, isEnd = false)
        val end = m.toOriginal(7, isEnd = true)
        assertEquals("Li sat", m.original.substring(start, end))
    }

    @Test
    fun `round trip through several replacements lands back on the same text`() {
        val m = shown("Li and Wei went to the Sect.", "Li" to "Lee", "Wei" to "Way", "Sect" to "Order")
        assertEquals("Lee and Way went to the Order.", m.text)
        val went = m.original.indexOf("went")
        val shownWent = m.toDisplay(went, isEnd = false)
        assertEquals("went", m.text.substring(shownWent, shownWent + 4))
        assertEquals(went, m.toOriginal(shownWent, isEnd = false))
    }

    // ── Formatting ───────────────────────────────────────────────────

    @Test
    fun `formatting and notes move with the text`() {
        // "Li said *softly*.1" style: italic on "softly", note on "1"
        val m = shown("Li said softly.1", "Li" to "Lee")
        val format = ParagraphFormat(
            spans = listOf(StyleSpan(0, 8, 14, InlineStyle.ITALIC)),
            notes = listOf(Footnote(0, 15, 16, "A note"))
        ).mapped(m)
        assertEquals("softly", m.text.substring(format.spans[0].start, format.spans[0].end))
        assertEquals("1", m.text.substring(format.notes[0].start, format.notes[0].end))
    }

    @Test
    fun `formatting on removed text is dropped`() {
        val m = shown("Junk here. Story.", "Junk here." to "")
        val format = ParagraphFormat(spans = listOf(StyleSpan(0, 0, 4, InlineStyle.BOLD))).mapped(m)
        assertTrue(format.spans.isEmpty())
    }

    // ── Which rules apply ────────────────────────────────────────────

    @Test
    fun `a book's own rule wins over an every-book rule for the same text`() {
        val all = listOf(
            TextReplacementEntity(id = 1, novelId = null, find = "Li", replace = "Lee"),
            TextReplacementEntity(id = 2, novelId = "book", find = "Li", replace = "Leigh"),
            TextReplacementEntity(id = 3, novelId = "other", find = "Wei", replace = "Way")
        )
        val manager = TextReplacementManager(NoDao)
        val rules = manager.rulesFor("book", all)
        assertEquals(listOf(2L, 1L), rules.map { it.id })
        val text = TextReplacements.apply(TextReplacementManager.compile(rules), "Li and Wei").text
        assertEquals("Leigh and Wei", text)
    }

    private object NoDao : com.abhinavxt.novelforge.data.database.TextReplacementDao {
        override fun observeAll() = throw UnsupportedOperationException()
        override suspend fun getAll() = emptyList<TextReplacementEntity>()
        override suspend fun insert(rule: TextReplacementEntity) = 0L
        override suspend fun update(id: Long, novelId: String?, find: String, replace: String) = Unit
        override suspend fun delete(id: Long) = Unit
    }

    @Test
    fun `replaced text finds the rule that produced it`() {
        val rules = listOf(
            TextReplacementEntity(id = 1, novelId = "book", find = "Li", replace = "Lee"),
            TextReplacementEntity(id = 2, novelId = "book", find = "gone", replace = "")
        )
        assertEquals(1L, TextReplacementManager.ruleShowing(rules, " lee, ")?.id)
        assertEquals(null, TextReplacementManager.ruleShowing(rules, "Li"))
        assertEquals(null, TextReplacementManager.ruleShowing(rules, "..."))
    }
}
