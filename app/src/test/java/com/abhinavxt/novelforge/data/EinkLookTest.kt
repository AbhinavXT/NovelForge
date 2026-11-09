package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.model.PageTransition
import com.abhinavxt.novelforge.data.model.ReaderTheme
import com.abhinavxt.novelforge.data.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EinkLookTest {

    private val before = EinkLook(AppTheme.NORD, ReaderTheme.SEPIA, ReadingMode.SCROLL, PageTransition.SLIDE)

    @Test
    fun turningOffPutsBackWhatEinkReplaced() {
        assertEquals(before, EinkLook.EINK.restoredTo(before))
    }

    @Test
    fun aSettingChangedWhileEinkWasOnIsKept() {
        val changed = EinkLook.EINK.copy(readerTheme = ReaderTheme.DARK, pageTransition = PageTransition.FADE)
        assertEquals(
            EinkLook(AppTheme.NORD, ReaderTheme.DARK, ReadingMode.SCROLL, PageTransition.FADE),
            changed.restoredTo(before)
        )
    }

    @Test
    fun encodingRoundTripsAndJunkIsIgnored() {
        assertEquals(before, EinkLook.decode(before.encode()))
        assertNull(EinkLook.decode(null))
        assertNull(EinkLook.decode("NORD|NOT_A_THEME|SCROLL|SLIDE"))
        assertNull(EinkLook.decode("NORD|SEPIA"))
    }
}
