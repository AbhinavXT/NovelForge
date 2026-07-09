package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One rendered history row, titles resolved. */
data class HistoryItem(
    val novelId: String,
    val novelTitle: String,
    val chapterLabel: String,
    val readingTimeMs: Long,
    val wordsRead: Int,
    val completedAt: Long
)

/** History rows bucketed under a day header ("Today", "Yesterday", date). */
data class HistoryDay(
    val label: String,
    val items: List<HistoryItem>
)

/**
 * Reading history (Phase 7): chronological "chapters I've read", derived
 * from the reading_stats events — no new table needed.
 */
class HistoryViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    private val _days = MutableStateFlow<List<HistoryDay>>(emptyList())
    val days: StateFlow<List<HistoryDay>> = _days.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            val rows = repository.getReadingHistory(limit = 300)

            // Resolve novel titles (same pattern as the stats screen fix)
            val titleById = rows
                .map { it.novelId }
                .distinct()
                .mapNotNull { id ->
                    repository.getNovelById(id)?.let { novel -> id to novel.title }
                }
                .toMap()

            val items = rows.map { row ->
                HistoryItem(
                    novelId = row.novelId,
                    novelTitle = titleById[row.novelId] ?: fallbackName(row.novelId),
                    chapterLabel = when {
                        row.chapterTitle != null && row.chapterNumber != null ->
                            "Ch. ${row.chapterNumber}: ${row.chapterTitle}"
                        row.chapterNumber != null -> "Chapter ${row.chapterNumber}"
                        else -> "Chapter"
                    },
                    readingTimeMs = row.readingTimeMs,
                    wordsRead = row.wordsRead,
                    completedAt = row.completedAt
                )
            }

            _days.value = groupByDay(items)
            _isLoading.value = false
        }
    }

    private fun fallbackName(novelId: String): String {
        if (novelId.startsWith("local_")) return "Imported book"
        return novelId
            .substringAfter("_")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
    }

    private fun groupByDay(items: List<HistoryItem>): List<HistoryDay> {
        val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val todayStart = dayStart(System.currentTimeMillis())
        val yesterdayStart = todayStart - 24L * 60 * 60 * 1000

        return items
            .groupBy { dayStart(it.completedAt) }
            .toSortedMap(compareByDescending { it })
            .map { (dayStartMs, dayItems) ->
                val label = when (dayStartMs) {
                    todayStart -> "Today"
                    yesterdayStart -> "Yesterday"
                    else -> dateFormat.format(Date(dayStartMs))
                }
                HistoryDay(label = label, items = dayItems)
            }
    }

    private fun dayStart(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(repository) as T
                }
            }
        }
    }
}
