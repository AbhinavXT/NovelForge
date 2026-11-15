package com.abhinavxt.novelforge.ui.viewmodel

import com.abhinavxt.novelforge.data.database.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressTest {

    @Test
    fun neverOpenedShowsNothing() {
        assertNull(bookProgress(null, 340, null))
        assertNull(bookProgress(0, 340, ReadingStatus.READING))
    }

    @Test
    fun noChaptersShowsNothing() {
        assertNull(bookProgress(5, 0, null))
    }

    @Test
    fun midBookIsCurrentOverTotal() {
        val p = bookProgress(12, 340, ReadingStatus.READING)!!
        assertFalse(p.finished)
        assertEquals(12f / 340f, p.fraction, 0.0001f)
    }

    @Test
    fun lastChapterIsFinished() {
        val p = bookProgress(340, 340, null)!!
        assertTrue(p.finished)
        assertEquals(1f, p.fraction, 0f)
    }

    @Test
    fun completedStatusIsFinishedEvenMidBook() {
        assertTrue(bookProgress(12, 340, ReadingStatus.COMPLETED)!!.finished)
    }

    @Test
    fun progressPastTheTotalIsClamped() {
        // Chapters removed after reading: still finished, never above 100%
        val p = bookProgress(400, 340, null)!!
        assertTrue(p.finished)
        assertEquals(1f, p.fraction, 0f)
    }
}
