package com.abhinavxt.novelforge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditedWordsTest {

    @Test
    fun aShortVisitToALongChapterIsCappedByTime() {
        // 30 seconds at most 1000 WPM.
        assertEquals(500, ReadingStatsTracker.creditedWords(45_838, 30_000))
    }

    @Test
    fun aChapterReadInFullCountsItsWords() {
        assertEquals(2_000, ReadingStatsTracker.creditedWords(2_000, 10 * 60_000))
    }
}
