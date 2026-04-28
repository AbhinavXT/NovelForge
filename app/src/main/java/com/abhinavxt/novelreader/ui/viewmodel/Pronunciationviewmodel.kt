package com.abhinavxt.novelreader.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.PronunciationIO
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.database.PronunciationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Pronunciation screen.
 *
 * Responsibilities:
 *  1. Expose the list of entries for the UI (unchanged from v1).
 *  2. Add / update / delete entries (unchanged).
 *  3. NEW: Import / export shareable dictionary files (.npd.json).
 *
 * Import/export UI flow on the screen:
 *   User taps "Import"      → screen launches OpenDocument picker
 *      → picker returns Uri → screen calls [previewImport]
 *      → VM parses file and exposes a [ImportPreviewState]
 *      → screen shows preview dialog with conflict-strategy picker
 *      → user confirms       → screen calls [commitImport]
 *      → VM writes to DB and exposes a [ImportResultMessage]
 *
 *   User taps "Export"      → screen launches CreateDocument picker
 *      → picker returns Uri → screen calls [exportToUri]
 *      → VM writes file and exposes an [ExportResultMessage]
 */
class PronunciationViewModel(
    private val manager: PronunciationManager
) : ViewModel() {

    // ── Existing state ──────────────────────────────────────────────

    private val _entries = MutableStateFlow<List<PronunciationEntry>>(emptyList())
    val entries: StateFlow<List<PronunciationEntry>> = _entries.asStateFlow()

    // ── NEW: import preview state ───────────────────────────────────
    //
    // Holds a parsed dictionary waiting for the user to confirm before we
    // actually touch the DB. Null means no preview is in flight.
    //
    // We also carry the parse-error case here so the screen can show a
    // single dialog surface for both "parsed OK, confirm?" and "parse failed".

    sealed class ImportPreviewState {
        /** Parse succeeded; waiting for user to commit or cancel. */
        data class Ready(
            val dictionary: PronunciationIO.Dictionary,
            /** How many of the incoming entries collide with existing words
             *  (case-insensitive). Used to decide whether to show the
             *  conflict-strategy picker at all. */
            val duplicateCount: Int
        ) : ImportPreviewState()

        /** Parse failed; show this message and offer a dismiss button. */
        data class Failed(val message: String) : ImportPreviewState()
    }

    private val _importPreview = MutableStateFlow<ImportPreviewState?>(null)
    val importPreview: StateFlow<ImportPreviewState?> = _importPreview.asStateFlow()

    // ── NEW: transient user-facing messages ────────────────────────
    //
    // After an import/export finishes we surface a short message for the UI
    // to snackbar. One-shot — the UI clears it via [clearTransientMessage].

    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()

    init {
        viewModelScope.launch {
            manager.getAllEntries().collect { _entries.value = it }
        }
    }

    // ── Existing entry CRUD (unchanged) ─────────────────────────────

    fun addEntry(word: String, replacement: String) {
        viewModelScope.launch { manager.addEntry(word, replacement) }
    }

    fun updateEntry(id: Long, word: String, replacement: String) {
        viewModelScope.launch { manager.updateEntry(id, word, replacement) }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { manager.deleteEntry(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { manager.deleteAll() }
    }

    // ── NEW: import flow ────────────────────────────────────────────

    /**
     * Parse a picked file and populate [importPreview].
     * Does NOT write anything to the DB — user must confirm via [commitImport].
     */
    fun previewImport(context: Context, uri: Uri) {
        viewModelScope.launch {
            when (val result = PronunciationIO.parseFromUri(context, uri)) {
                is PronunciationIO.ParseResult.Success -> {
                    // Count duplicates up front so the UI can decide whether
                    // to show a conflict-strategy picker. If zero duplicates,
                    // we can just use SKIP_DUPLICATES silently and skip the
                    // extra UI question.
                    val existing = manager.getAllForBackup()
                        .map { it.word.lowercase() }
                        .toHashSet()
                    val dupes = result.dictionary.entries
                        .count { (word, _) -> existing.contains(word.lowercase()) }

                    _importPreview.value = ImportPreviewState.Ready(
                        dictionary = result.dictionary,
                        duplicateCount = dupes
                    )
                }
                is PronunciationIO.ParseResult.Error -> {
                    _importPreview.value = ImportPreviewState.Failed(result.message)
                }
            }
        }
    }

    /**
     * User confirmed the preview — write entries to the DB with the chosen strategy.
     */
    fun commitImport(strategy: PronunciationIO.ConflictStrategy) {
        val preview = _importPreview.value
        if (preview !is ImportPreviewState.Ready) return

        viewModelScope.launch {
            val report = PronunciationIO.importDictionary(
                manager = manager,
                dictionary = preview.dictionary,
                strategy = strategy
            )

            // Build a friendly summary message. Three cases:
            //   - all new  → "Imported 42 entries"
            //   - some dupes → "Imported 35 new entries (7 skipped)"
            //   - nothing   → "No new entries added"
            val msg = when {
                report.inserted == 0 -> "No new entries added"
                report.skippedDuplicates == 0 ->
                    "Imported ${report.inserted} entries"
                else ->
                    "Imported ${report.inserted} entries " +
                            "(${report.skippedDuplicates} skipped)"
            }
            _transientMessage.value = msg
            _importPreview.value = null
        }
    }

    /** User dismissed the preview without importing. */
    fun cancelImport() {
        _importPreview.value = null
    }

    // ── NEW: export flow ────────────────────────────────────────────

    /**
     * Write the current dictionary to a user-chosen Uri as JSON.
     *
     * @param title    User-provided title for the dictionary (shown at the top
     *                 of the file and to whoever imports it later).
     */
    fun exportToUri(
        context: Context,
        uri: Uri,
        title: String,
        description: String = ""
    ) {
        viewModelScope.launch {
            val current = manager.getAllForBackup()
            if (current.isEmpty()) {
                _transientMessage.value = "Dictionary is empty — nothing to export"
                return@launch
            }

            val json = PronunciationIO.buildJsonString(
                entries = current,
                title = title.ifBlank { "My Pronunciation Dictionary" },
                description = description
            )
            val ok = PronunciationIO.writeToUri(context, uri, json)
            _transientMessage.value = if (ok) {
                "Exported ${current.size} entries"
            } else {
                "Export failed — check storage permissions"
            }
        }
    }

    /**
     * Build the suggested filename for the file picker's initial suggestion.
     * Exposed as a helper so the screen doesn't need to import PronunciationIO.
     */
    fun suggestedExportFilename(): String = PronunciationIO.suggestedFilename()

    /** Called by the UI after it has consumed a transient message. */
    fun clearTransientMessage() {
        _transientMessage.value = null
    }

    class Factory(private val manager: PronunciationManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PronunciationViewModel(manager) as T
        }
    }
}