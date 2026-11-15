package com.abhinavxt.novelforge.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPresetsTest {

    // Non-default values for fields a preset must leave alone.
    private val base = ReaderSettings(
        readingMode = ReadingMode.PAGED,
        pageTransition = PageTransition.CURL,
        keepScreenOn = false,
        volumeKeyNavigation = true,
        autoScrollSpeed = 120,
        tapZoneLayout = TapZoneLayout.L_SHAPED,
        paragraphIndent = true,
        customBackgroundColor = 0xFF123456,
        bookLanguage = "de",
    )

    /** [s] with every look field a preset may set blanked, leaving only what must be untouched. */
    private fun untouched(s: ReaderSettings) = s.copy(
        fontSize = 0, theme = ReaderTheme.PAPER, font = ReaderFont.LITERATA, lineSpacing = 0f,
        horizontalMargin = 0, justifyText = false, customFont = null, hyphenation = false,
        paragraphSpacing = 0, dropCap = false,
    )

    @Test
    fun presetsOnlyChangeLookFields() {
        ReaderPreset.values().forEach { p ->
            assertEquals(p.name, untouched(base), untouched(p.applyTo(base)))
        }
    }

    @Test
    fun paperSetsTheWholeLookAndClearsAnImportedFont() {
        val s = ReaderPreset.PAPER.applyTo(base.copy(theme = ReaderTheme.DARK, customFont = "/f.ttf"))
        assertEquals(ReaderTheme.PAPER, s.theme)
        assertEquals(ReaderFont.LITERATA, s.font)
        assertNull(s.customFont)
        assertTrue(s.justifyText && s.hyphenation && s.dropCap)
    }

    @Test
    fun applyingTwiceChangesNothingMore() {
        ReaderPreset.values().forEach { assertEquals(it.applyTo(base), it.applyTo(it.applyTo(base))) }
    }

    @Test
    fun aPresetIsSelectedOnlyWhileTheSettingsMatch() {
        val night = ReaderPreset.NIGHT.applyTo(base)
        assertTrue(ReaderPreset.NIGHT.matches(night))
        assertFalse(ReaderPreset.FOCUS.matches(night))
        assertFalse(ReaderPreset.NIGHT.matches(night.copy(justifyText = true)))
        assertFalse(ReaderPreset.NIGHT.matches(night.copy(theme = ReaderTheme.AMOLED)))
    }

    @Test
    fun slidersLandingAHairOffStillMatch() {
        val focus = ReaderPreset.FOCUS.applyTo(base)
        assertTrue(ReaderPreset.FOCUS.matches(focus.copy(lineSpacing = 2.0000002f)))
        assertFalse(ReaderPreset.FOCUS.matches(focus.copy(lineSpacing = 1.8f)))
    }

    @Test
    fun changingANonLookFieldKeepsAPresetSelected() {
        val compact = ReaderPreset.COMPACT.applyTo(base)
        assertTrue(ReaderPreset.COMPACT.matches(compact.copy(readingMode = ReadingMode.SCROLL)))
    }
}
