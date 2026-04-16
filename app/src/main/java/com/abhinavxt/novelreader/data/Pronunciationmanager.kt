package com.abhinavxt.novelreader.data

import com.abhinavxt.novelreader.data.database.PronunciationDao
import com.abhinavxt.novelreader.data.database.PronunciationEntry
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages a pronunciation dictionary: word → phonetic replacement.
 * TTS text passes through [applyReplacements] before being spoken,
 * so "Xiulan" becomes "shoo-lan" etc.
 *
 * Keeps an in-memory cache of entries so replacement is O(n) per entry,
 * not a DB hit per sentence.
 */
class PronunciationManager(private val dao: PronunciationDao) {

    // In-memory cache, rebuilt on any change
    private val _entries = MutableStateFlow<List<PronunciationEntry>>(emptyList())
    val entries: StateFlow<List<PronunciationEntry>> = _entries.asStateFlow()

    // Pre-compiled patterns (#26) — rebuilt when cache changes
    @Volatile
    private var compiledPatterns: List<Pair<Regex, String>> = emptyList()

    // Flow for UI observation
    fun getAllEntries(): Flow<List<PronunciationEntry>> = dao.getAllEntries()

    /**
     * Load entries into memory. Call once at startup.
     */
    suspend fun loadCache() {
        withContext(Dispatchers.IO) {
            val entries = dao.getAllEntriesOnce()
            _entries.value = entries
            rebuildPatterns(entries)
            Logger.d("PronunciationMgr", "Loaded ${entries.size} pronunciation entries")
        }
    }

    /**
     * Pre-compile regex patterns from entries.
     * Uses Unicode-aware word boundaries (#27) instead of \b
     * so accented names (Naëlith) and CJK names match correctly.
     */
    private fun rebuildPatterns(entries: List<PronunciationEntry>) {
        compiledPatterns = entries.map { entry ->
            val escaped = Regex.escape(entry.word)
            // Unicode-aware boundaries: match at start/end of string, whitespace, or punctuation
            val pattern = Regex(
                "(?<=^|[\\s\\p{Punct}])$escaped(?=$|[\\s\\p{Punct}])",
                RegexOption.IGNORE_CASE
            )
            pattern to entry.replacement
        }
    }

    /**
     * Apply all pronunciation replacements to the given text.
     * Uses pre-compiled patterns — no regex compilation per call.
     */
    fun applyReplacements(text: String): String {
        val patterns = compiledPatterns
        if (patterns.isEmpty()) return text

        var result = text
        for ((pattern, replacement) in patterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    suspend fun addEntry(word: String, replacement: String): Long {
        val trimmedWord = word.trim()
        val trimmedReplacement = replacement.trim()
        if (trimmedWord.isBlank() || trimmedReplacement.isBlank()) return -1

        val id = withContext(Dispatchers.IO) {
            dao.insertEntry(
                PronunciationEntry(word = trimmedWord, replacement = trimmedReplacement)
            )
        }
        refreshCache()
        Logger.d("PronunciationMgr", "Added: $trimmedWord → $trimmedReplacement")
        return id
    }

    suspend fun updateEntry(id: Long, word: String, replacement: String) {
        withContext(Dispatchers.IO) {
            dao.updateEntry(id, word.trim(), replacement.trim())
        }
        refreshCache()
    }

    suspend fun deleteEntry(id: Long) {
        withContext(Dispatchers.IO) {
            dao.deleteEntry(id)
        }
        refreshCache()
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            dao.deleteAll()
        }
        refreshCache()
    }

    private suspend fun refreshCache() {
        withContext(Dispatchers.IO) {
            val entries = dao.getAllEntriesOnce()
            _entries.value = entries
            rebuildPatterns(entries)
        }
    }

    // Backup/restore
    suspend fun getAllForBackup(): List<PronunciationEntry> {
        return withContext(Dispatchers.IO) { dao.getAllEntriesOnce() }
    }

    suspend fun insertForRestore(entry: PronunciationEntry) {
        withContext(Dispatchers.IO) { dao.insertEntry(entry) }
        refreshCache()
    }
}