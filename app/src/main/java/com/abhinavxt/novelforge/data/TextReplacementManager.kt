package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.TextReplacementDao
import com.abhinavxt.novelforge.data.database.TextReplacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the text replacement rules: storage, an in-memory copy, and the
 * compiled matcher per book. The reader shows text through it and TTS speaks
 * through it, so what you hear is what you read.
 */
class TextReplacementManager(private val dao: TextReplacementDao) {

    private val _rules = MutableStateFlow<List<TextReplacementEntity>>(emptyList())
    val rules: StateFlow<List<TextReplacementEntity>> = _rules.asStateFlow()

    // Compiled per book (key "" = no book), dropped whenever a rule changes.
    private val compiled = ConcurrentHashMap<String, PronunciationRules.Compiled>()

    suspend fun loadCache() = refresh()

    /** The rules that apply to [novelId]: its own, then those for every book. */
    fun rulesFor(novelId: String?, all: List<TextReplacementEntity> = _rules.value): List<TextReplacementEntity> =
        all.filter { it.novelId != null && it.novelId == novelId } + all.filter { it.novelId == null }

    /**
     * The matcher for [novelId]. A book's own rule wins over an every-book
     * rule for the same text (the compiler keeps the first of equal length).
     */
    fun compiledFor(novelId: String?): PronunciationRules.Compiled =
        compiled.getOrPut(novelId.orEmpty()) { compile(rulesFor(novelId)) }

    /** [text] as the reader shows it for [novelId]. */
    fun apply(novelId: String?, text: String): String {
        val rules = compiledFor(novelId)
        return if (rules.isEmpty) text else TextReplacements.apply(rules, text).text
    }

    /** @param novelId null for every book */
    suspend fun add(novelId: String?, find: String, replace: String): Long {
        val word = find.trim()
        if (word.isEmpty()) return -1
        val id = withContext(Dispatchers.IO) {
            dao.insert(TextReplacementEntity(novelId = novelId, find = word, replace = replace.trim()))
        }
        refresh()
        return id
    }

    suspend fun update(id: Long, novelId: String?, find: String, replace: String) {
        val word = find.trim()
        if (word.isEmpty()) return
        withContext(Dispatchers.IO) { dao.update(id, novelId, word, replace.trim()) }
        refresh()
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { dao.delete(id) }
        refresh()
    }

    // Backup/restore
    suspend fun getAllForBackup(): List<TextReplacementEntity> = withContext(Dispatchers.IO) { dao.getAll() }

    /**
     * Adds [rules] from a backup, skipping any already here (same book, same
     * text), since restore doesn't clear what's present first.
     * @return how many were added
     */
    suspend fun insertForRestore(rules: List<TextReplacementEntity>): Int {
        val added = withContext(Dispatchers.IO) {
            val seen = dao.getAll().map { it.novelId to it.find.lowercase() }.toMutableSet()
            rules.count { rule ->
                val key = rule.novelId to rule.find.trim().lowercase()
                if (rule.find.isBlank() || !seen.add(key)) return@count false
                dao.insert(rule.copy(id = 0, find = rule.find.trim(), replace = rule.replace.trim()))
                true
            }
        }
        refresh()
        return added
    }

    private suspend fun refresh() {
        val all = withContext(Dispatchers.IO) { dao.getAll() }
        _rules.value = all
        compiled.clear()
    }

    companion object {
        fun compile(rules: List<TextReplacementEntity>): PronunciationRules.Compiled =
            PronunciationRules.compile(rules.map { PronunciationRules.Rule(it.find, it.replace) })
    }
}
