package com.abhinavxt.novelforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontsTest {

    @Test
    fun `recognises font files by their first bytes`() {
        assertTrue(ReaderFonts.isFontHeader(byteArrayOf(0, 1, 0, 0)))
        assertTrue(ReaderFonts.isFontHeader("OTTO".toByteArray()))
        assertTrue(ReaderFonts.isFontHeader("ttcf".toByteArray()))
        assertFalse(ReaderFonts.isFontHeader("wOFF".toByteArray()))
        assertFalse(ReaderFonts.isFontHeader("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun `file names can't leave the fonts folder`() {
        assertEquals("Literata Bold.font", ReaderFonts.fileName("Literata Bold.ttf"))
        assertEquals("evil.font", ReaderFonts.fileName("../../databases/evil.ttf"))
        assertEquals("evil.font", ReaderFonts.fileName("..\\evil.otf"))
        assertEquals("_.font", ReaderFonts.fileName(".."))
        assertEquals("Font.font", ReaderFonts.fileName(".ttf"))
        assertEquals("思源宋体.font", ReaderFonts.fileName("思源宋体.otf"))
        assertEquals(105, ReaderFonts.fileName("a".repeat(300) + ".ttf").length)
    }
}
