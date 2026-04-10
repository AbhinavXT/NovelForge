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

    // Flow for UI observation
    fun getAllEntries(): Flow<List<PronunciationEntry>> = dao.getAllEntries()

    /**
     * Load entries into memory. Call once at startup.
     */
    suspend fun loadCache() {
        withContext(Dispatchers.IO) {
            _entries.value = dao.getAllEntriesOnce()
            Logger.d("PronunciationMgr", "Loaded ${_entries.value.size} pronunciation entries")
        }
    }

    /**
     * Apply all pronunciation replacements to the given text.
     * Case-insensitive whole-word matching so "Xiulan" matches
     * "xiulan" and "XIULAN" but not "Xiulander".
     */
    fun applyReplacements(text: String): String {
        val cached = _entries.value
        if (cached.isEmpty()) return text

        var result = text
        for (entry in cached) {
            // Word-boundary regex for case-insensitive whole-word match
            val pattern = Regex("\\b${Regex.escape(entry.word)}\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, entry.replacement)
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
            _entries.value = dao.getAllEntriesOnce()
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