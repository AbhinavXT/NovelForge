package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.ReadingStatEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One calendar year of reading, summed up from the stored stat rows.
 *
 * What the data supports: every row carries a novel, words, time and a
 * timestamp, so time, words, chapters, days, streaks, busiest month/day
 * and per-book totals are all exact. What it does NOT support: when a book
 * was finished. A book's COMPLETED status has no date and chapter rows are
 * not tied to "last chapter", so books finished this year are deliberately
 * not part of this summary rather than guessed.
 */
data class YearInReview(
    val year: Int,
    val totalTimeMs: Long,
    val totalWords: Int,
    val chapters: Int,
    val daysRead: Int,
    /** Longest run of consecutive reading days that lies inside [year]. */
    val longestStreak: Int,
    /** Reading time per month, index 0 = January. Always 12 entries. */
    val monthTimeMs: List<Long>,
    /** 1..12, or null when nothing was read. */
    val busiestMonth: Int?,
    val busiestDay: BusyDay?,
    /** Top books by reading time (ties broken by chapters), at most [TOP_BOOKS]. */
    val topBooks: List<BookTotal>,
    val booksRead: Int
) {
    val isEmpty: Boolean get() = chapters == 0

    data class BusyDay(val date: LocalDate, val timeMs: Long, val words: Int)

    data class BookTotal(val novelId: String, val timeMs: Long, val chapters: Int, val words: Int)

    companion object {
        const val TOP_BOOKS = 3

        /**
         * Rows outside [year] (in [zone]) are ignored, so callers may pass a
         * padded range. Days are local dates, which keeps streaks correct
         * across daylight-saving changes.
         */
        fun compute(
            events: List<ReadingStatEvent>,
            year: Int,
            zone: ZoneId = ZoneId.systemDefault()
        ): YearInReview {
            val inYear = events.mapNotNull { e ->
                val date = Instant.ofEpochMilli(e.completedAt).atZone(zone).toLocalDate()
                if (date.year == year) date to e else null
            }

            val monthTime = LongArray(12)
            inYear.forEach { (date, e) -> monthTime[date.monthValue - 1] += e.readingTimeMs }
            // Most time wins, then most rows, then the earlier month, so a year of zero-time rows still names a month.
            val busiestMonth = if (inYear.isEmpty()) null else {
                val rows = IntArray(12)
                inYear.forEach { rows[it.first.monthValue - 1]++ }
                (1..12).maxWithOrNull(
                    compareBy<Int> { monthTime[it - 1] }.thenBy { rows[it - 1] }.thenByDescending { it }
                )
            }

            val byDay = inYear.groupBy({ it.first }, { it.second })
            val busiestDay = byDay.entries
                .map { (d, es) -> BusyDay(d, es.sumOf { it.readingTimeMs }, es.sumOf { it.wordsRead }) }
                .sortedWith(compareByDescending<BusyDay> { it.timeMs }.thenBy { it.date })
                .firstOrNull()

            val topBooks = inYear.groupBy({ it.second.novelId }, { it.second })
                .map { (id, es) ->
                    BookTotal(id, es.sumOf { it.readingTimeMs }, es.size, es.sumOf { it.wordsRead })
                }

            var longest = 0
            var run = 0
            var previous: LocalDate? = null
            byDay.keys.sorted().forEach { d ->
                run = if (previous != null && previous!!.plusDays(1) == d) run + 1 else 1
                if (run > longest) longest = run
                previous = d
            }

            return YearInReview(
                year = year,
                totalTimeMs = inYear.sumOf { it.second.readingTimeMs },
                totalWords = inYear.sumOf { it.second.wordsRead },
                chapters = inYear.size,
                daysRead = byDay.size,
                longestStreak = longest,
                monthTimeMs = monthTime.toList(),
                busiestMonth = busiestMonth,
                busiestDay = busiestDay,
                topBooks = topBooks
                    .sortedWith(compareByDescending<BookTotal> { it.timeMs }.thenByDescending { it.chapters }.thenBy { it.novelId })
                    .take(TOP_BOOKS),
                booksRead = topBooks.size
            )
        }

        /** Years that have at least one row, newest first. */
        fun yearsWithData(timestamps: List<Long>, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
            timestamps.map { Instant.ofEpochMilli(it).atZone(zone).year }.distinct().sortedDescending()
    }
}
