package com.abhinavxt.novelforge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {

    private val red = 0xFFD32F2F.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val darkSurface = 0xFF1C1B1F.toInt()
    private val lightSurface = 0xFFFFFBFE.toInt()

    @Test
    fun pureRedImageYieldsRed() {
        val c = CoverPalette.dominantColor(IntArray(48 * 72) { red })
        assertEquals(red, c)
    }

    @Test
    fun greyscaleAndBlankImagesYieldNull() {
        assertNull(CoverPalette.dominantColor(IntArray(100) { 0xFF808080.toInt() }))
        assertNull(CoverPalette.dominantColor(IntArray(100) { white }))
        assertNull(CoverPalette.dominantColor(IntArray(100) { black }))
        assertNull(CoverPalette.dominantColor(IntArray(0)))
    }

    @Test
    fun heaviestHueWinsOverBackgroundNoise() {
        val blue = 0xFF1565C0.toInt()
        val pixels = IntArray(100) { if (it < 60) blue else if (it < 80) red else white }
        assertEquals(blue, CoverPalette.dominantColor(pixels))
    }

    @Test
    fun accentReachesContrastOnLightAndDarkSurfaces() {
        val accents = listOf(red, 0xFFFFEB3B.toInt(), 0xFF1565C0.toInt(), 0xFF4CAF50.toInt(), 0xFF7E57C2.toInt())
        for (accent in accents) {
            for (surface in listOf(darkSurface, lightSurface, black, white)) {
                val adapted = CoverPalette.accentOn(accent, surface)
                assertTrue(
                    "accent %08X on %08X".format(accent, surface),
                    CoverPalette.contrast(adapted, surface) >= 4.5
                )
            }
        }
    }

    @Test
    fun accentThatAlreadyContrastsIsLeftAlone() {
        assertEquals(red, CoverPalette.accentOn(red, lightSurface))
    }

    @Test
    fun onAccentIsReadableBlackOrWhite() {
        assertEquals(black, CoverPalette.onAccent(0xFFFFEB3B.toInt()))
        assertEquals(white, CoverPalette.onAccent(0xFF1A237E.toInt()))
        assertNotNull(CoverPalette.onAccent(red))
        assertTrue(CoverPalette.contrast(red, CoverPalette.onAccent(red)) >= 4.5)
    }
}
