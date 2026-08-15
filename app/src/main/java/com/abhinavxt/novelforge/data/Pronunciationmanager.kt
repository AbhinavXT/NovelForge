package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.PronunciationDao
import com.abhinavxt.novelforge.data.database.PronunciationEntry
import com.abhinavxt.novelforge.util.Logger
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

    // Pre-compiled rules — rebuilt when cache changes
    @Volatile
    private var compiled: PronunciationRules.Compiled =
        PronunciationRules.compile(emptyList())

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
     * Pre-compile the dictionary into a single matcher.
     *
     * Matching semantics live in [PronunciationRules] so they can be unit
     * tested without Room. Notably it compiles ONE combined pattern rather
     * than one per entry: the old per-entry loop ran each pattern over the
     * previous result, so a replacement could be re-matched by a later rule
     * (Li→Lee plus Lee→Leigh turned "Li" into "Leigh").
     */
    private fun rebuildPatterns(entries: List<PronunciationEntry>) {
        compiled = PronunciationRules.compile(
            entries.map { PronunciationRules.Rule(it.word, it.replacement) }
        )
    }

    /**
     * Apply all pronunciation replacements to the given text.
     * Uses the pre-compiled matcher — no regex compilation per call.
     *
     * An entry with an empty replacement is removed from the spoken text
     * rather than substituted, which is how symbol skipping works.
     */
    fun applyReplacements(text: String): String = compiled.apply(text)

    /**
     * @param replacement empty means "do not speak this at all". Only [word]
     *   is required -- the old guard rejected a blank replacement, which made
     *   it impossible to silence a symbol or an unwanted word.
     */
    suspend fun addEntry(word: String, replacement: String): Long {
        val trimmedWord = word.trim()
        val trimmedReplacement = replacement.trim()
        if (trimmedWord.isBlank()) return -1

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

    /**
     * Add every symbol in [PronunciationRules.SKIPPABLE_SYMBOLS] as a skip
     * rule, leaving any the user already configured untouched.
     *
     * @return how many rules were actually added
     */
    suspend fun addSymbolSkipPreset(): Int {
        val existing = withContext(Dispatchers.IO) { dao.getAllEntriesOnce() }
            .map { it.word }
            .toSet()
        val missing = PronunciationRules.SKIPPABLE_SYMBOLS.filter { it !in existing }
        if (missing.isEmpty()) return 0

        withContext(Dispatchers.IO) {
            missing.forEach { symbol ->
                dao.insertEntry(PronunciationEntry(word = symbol, replacement = ""))
            }
        }
        refreshCache()
        Logger.d("PronunciationMgr", "Added ${missing.size} symbol skip rules")
        return missing.size
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