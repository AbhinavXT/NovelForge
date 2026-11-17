package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.ReadingStatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class YearInReviewTest {

    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L

    private fun row(date: String, novel: String, words: Int, minutes: Int, hour: Int = 12) =
        ReadingStatEvent(
            novelId = novel,
            chapterId = "$novel-$date-$hour",
            wordsRead = words,
            readingTimeMs = minutes * minute,
            completedAt = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        )

    @Test
    fun emptyYearIsEmpty() {
        val r = YearInReview.compute(emptyList(), 2025, zone)
        assertTrue(r.isEmpty)
        assertEquals(0, r.daysRead)
        assertEquals(0, r.longestStreak)
        assertNull(r.busiestMonth)
        assertNull(r.busiestDay)
        assertEquals(12, r.monthTimeMs.size)
        assertTrue(r.topBooks.isEmpty())
    }

    @Test
    fun rowsFromOtherYearsAreIgnored() {
        val r = YearInReview.compute(listOf(row("2024-06-01", "a", 100, 10)), 2025, zone)
        assertTrue(r.isEmpty)
    }

    @Test
    fun totalsMonthsAndBusiestDay() {
        val rows = listOf(
            row("2025-03-10", "a", 1000, 30),
            row("2025-03-10", "a", 500, 20, hour = 20),
            row("2025-03-11", "b", 800, 10),
            row("2025-07-01", "b", 200, 40)
        )
        val r = YearInReview.compute(rows, 2025, zone)
        assertEquals(2500, r.totalWords)
        assertEquals(100 * minute, r.totalTimeMs)
        assertEquals(4, r.chapters)
        assertEquals(3, r.daysRead)
        assertEquals(2, r.longestStreak)
        assertEquals(3, r.busiestMonth) // 60 min in March vs 40 in July
        assertEquals(60 * minute, r.monthTimeMs[2])
        assertEquals(LocalDate.parse("2025-03-10"), r.busiestDay!!.date)
        assertEquals(50 * minute, r.busiestDay!!.timeMs)
        assertEquals(1500, r.busiestDay!!.words)
    }

    @Test
    fun topBooksRankByTimeThenChapters() {
        val rows = listOf(
            row("2025-01-01", "a", 1, 10),
            row("2025-01-02", "b", 1, 50),
            row("2025-01-03", "c", 1, 20),
            row("2025-01-04", "c", 1, 20),
            row("2025-01-05", "d", 1, 5),
            row("2025-01-06", "e", 1, 40)
        )
        val r = YearInReview.compute(rows, 2025, zone)
        assertEquals(listOf("b", "c", "e"), r.topBooks.map { it.novelId })
        assertEquals(2, r.topBooks[1].chapters)
        assertEquals(5, r.booksRead)
    }

    @Test
    fun streakCrossingNewYearCountsOnlyDaysInsideTheYear() {
        val rows = listOf(
            row("2024-12-29", "a", 1, 1),
            row("2024-12-30", "a", 1, 1),
            row("2024-12-31", "a", 1, 1),
            row("2025-01-01", "a", 1, 1),
            row("2025-01-02", "a", 1, 1),
            row("2025-01-10", "a", 1, 1)
        )
        assertEquals(2, YearInReview.compute(rows, 2025, zone).longestStreak)
        assertEquals(3, YearInReview.compute(rows, 2024, zone).longestStreak)
        assertEquals(3, YearInReview.compute(rows, 2025, zone).chapters)
    }

    @Test
    fun yearBoundaryFollowsTheZone() {
        // 23:30 UTC on 31 Dec is already 1 Jan in Tokyo.
        val e = row("2024-12-31", "a", 1, 1, hour = 23)
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertTrue(YearInReview.compute(listOf(e), 2025, tokyo).chapters == 1)
        assertTrue(YearInReview.compute(listOf(e), 2024, tokyo).isEmpty)
    }

    @Test
    fun yearsWithDataAreNewestFirst() {
        val ts = listOf("2023-05-01", "2025-01-01", "2023-06-01").map {
            LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        assertEquals(listOf(2025, 2023), YearInReview.yearsWithData(ts, zone))
    }
}
