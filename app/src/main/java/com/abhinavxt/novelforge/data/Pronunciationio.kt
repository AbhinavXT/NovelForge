package com.abhinavxt.novelforge.data

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelforge.data.database.PronunciationEntry
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shareable pronunciation dictionary import/export.
 *
 * File format: .npd.json — "NovelForge Pronunciation Dictionary"
 * Plain JSON (not a binary format) so users can hand-edit, peer-review on GitHub,
 * diff in PRs, and open in any text editor. The format is versioned from day one
 * so we can evolve it without breaking old files.
 *
 * Wire format example:
 * {
 *   "format": "novelforge-pronunciation/v1",
 *   "title": "Cultivation Novels — Chinese Terms",
 *   "description": "Common cultivation terminology",
 *   "language": "zh-en",
 *   "author": "Abhinav",
 *   "createdAt": 1737000000000,
 *   "entries": [
 *     {"word": "Xiulan", "replacement": "shoo-lan"},
 *     {"word": "Qi",     "replacement": "chee"}
 *   ]
 * }
 *
 * Design choices:
 *  • JSON, not CSV — CSV breaks the moment someone has a comma or quote in a
 *    pronunciation (and commas are very common: "dee-oh-en-ay, pronounced like
 *    DNA"). JSON is unambiguous.
 *  • Versioned format string at the top — lets us migrate v1 → v2 later
 *    without guessing.
 *  • metadata fields (title, description, language, author) are all optional
 *    on parse — hand-written dictionaries won't have them and that's fine.
 *  • On import we DON'T insert id values from the file. Every entry becomes
 *    a fresh row (id auto-generated) so ids from one user's DB don't collide
 *    with another's.
 */
object PronunciationIO {

    private const val TAG = "PronunciationIO"
    private const val FORMAT_V1 = "novelforge-pronunciation/v1"

    // ─────────────────────────────────────────────────────────────────
    // Public data classes
    // ─────────────────────────────────────────────────────────────────

    /**
     * Metadata + entries read from (or written to) a .npd.json file.
     * Used by the UI to preview a file before the user commits to importing.
     */
    data class Dictionary(
        val title: String,
        val description: String,
        val language: String,
        val author: String,
        val createdAt: Long,
        val entries: List<Pair<String, String>>   // (word, replacement)
    )

    /**
     * Result of a parse attempt. Sealed so the UI can render a clean
     * error state without scattering null-checks.
     */
    sealed class ParseResult {
        data class Success(val dictionary: Dictionary) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    /**
     * Result of an import — tells the UI how many were new vs skipped
     * so it can show "Imported 42 new entries (8 duplicates skipped)".
     */
    data class ImportReport(
        val inserted: Int,
        val skippedDuplicates: Int,
        val total: Int
    )

    /**
     * Conflict-resolution strategy when an imported word already exists.
     * Decided by the user at the preview dialog.
     */
    enum class ConflictStrategy {
        /** Keep the existing entry, skip the new one. Safe default. */
        SKIP_DUPLICATES,

        /** Overwrite existing entry with the new replacement. */
        REPLACE,

        /** Insert both. User ends up with two rows for the same word; the
         *  TTS pipeline will apply both patterns in insertion order. Rarely
         *  useful but we support it for completeness. */
        KEEP_BOTH
    }

    // ─────────────────────────────────────────────────────────────────
    // Parse — called when the user picks a .npd.json file
    // ─────────────────────────────────────────────────────────────────

    /**
     * Read a Uri from the file picker and parse it into a Dictionary.
     * Runs on IO dispatcher because we're reading from a stream.
     */
    suspend fun parseFromUri(context: Context, uri: Uri): ParseResult =
        withContext(Dispatchers.IO) {
            try {
                // Open the input stream. SAF URIs give us a stream, not a path.
                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Read the whole file into a string. Pronunciation dicts are tiny
                    // (a few KB at most even for the biggest community packs), so
                    // loading it all into memory is fine — we don't need streaming JSON.
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                        .use { it.readText() }
                } ?: return@withContext ParseResult.Error("Could not open file.")

                parseJsonString(raw)
            } catch (e: Exception) {
                Logger.e(TAG, "parseFromUri failed", e)
                ParseResult.Error("Read failed: ${e.message ?: "unknown error"}")
            }
        }

    /**
     * Parse a raw JSON string. Exposed separately so unit tests (and future
     * clipboard paste flow) can feed a string directly without a Uri.
     */
    fun parseJsonString(raw: String): ParseResult {
        return try {
            val root = JSONObject(raw)

            // Validate the format marker. If it's missing or unknown we bail
            // with a clear message rather than silently trying to import junk.
            val format = root.optString("format", "")
            if (format.isBlank()) {
                return ParseResult.Error(
                    "This file is missing the 'format' field — it may not be a " +
                            "NovelForge pronunciation dictionary."
                )
            }
            if (format != FORMAT_V1) {
                return ParseResult.Error(
                    "Unsupported format: '$format'. This app reads '$FORMAT_V1'."
                )
            }

            // entries[] is the only required field. Everything else is optional.
            val entriesArray = root.optJSONArray("entries")
                ?: return ParseResult.Error("File has no 'entries' list.")

            if (entriesArray.length() == 0) {
                return ParseResult.Error("Dictionary is empty.")
            }

            // Parse each entry. Skip malformed rows rather than aborting the
            // whole import — hand-edited files often have a stray typo in one
            // row and it's friendlier to import the 99 good ones.
            val parsed = mutableListOf<Pair<String, String>>()
            var malformed = 0
            for (i in 0 until entriesArray.length()) {
                // Fetch the row. If the row isn't a JSON object at all
                // (e.g. a stray string in the array), bump the malformed
                // counter and move on.
                val obj = entriesArray.optJSONObject(i)
                if (obj == null) {
                    malformed++
                    continue
                }
                val word = obj.optString("word", "").trim()
                val replacement = obj.optString("replacement", "").trim()
                if (word.isEmpty() || replacement.isEmpty()) {
                    malformed++
                    continue
                }
                parsed.add(word to replacement)
            }

            if (parsed.isEmpty()) {
                return ParseResult.Error(
                    "No valid entries found (all $malformed rows were malformed)."
                )
            }

            if (malformed > 0) {
                Logger.d(TAG, "Skipped $malformed malformed entries during parse")
            }

            val dict = Dictionary(
                title = root.optString("title", "Untitled dictionary"),
                description = root.optString("description", ""),
                language = root.optString("language", ""),
                author = root.optString("author", ""),
                createdAt = root.optLong("createdAt", 0L),
                entries = parsed
            )
            ParseResult.Success(dict)
        } catch (e: Exception) {
            Logger.e(TAG, "JSON parse failed", e)
            ParseResult.Error("Invalid JSON: ${e.message ?: "parser error"}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Import — writes parsed entries into the database
    // ─────────────────────────────────────────────────────────────────

    /**
     * Import a parsed Dictionary into the pronunciation manager.
     * Handles duplicate resolution based on [strategy].
     *
     * Duplicate detection is case-insensitive on the 'word' field — we assume
     * that if the user already has "Xiulan → shoo-lan" and the imported pack
     * has "xiulan → shoo-lahn", those are meant to be the same.
     */
    suspend fun importDictionary(
        manager: PronunciationManager,
        dictionary: Dictionary,
        strategy: ConflictStrategy
    ): ImportReport = withContext(Dispatchers.IO) {
        // Load current entries once to build a set of existing words for
        // conflict detection. Doing this once is O(n+m) vs. O(n*m) per-entry.
        val existing = manager.getAllForBackup()
            .associateBy { it.word.lowercase() }

        var inserted = 0
        var skipped = 0

        for ((word, replacement) in dictionary.entries) {
            val key = word.lowercase()
            val existingEntry = existing[key]

            when {
                existingEntry == null -> {
                    // No conflict — straightforward insert.
                    manager.addEntry(word, replacement)
                    inserted++
                }

                strategy == ConflictStrategy.SKIP_DUPLICATES -> {
                    skipped++
                }

                strategy == ConflictStrategy.REPLACE -> {
                    // Update the existing row in place so its id is preserved.
                    // If the replacement is already identical we still count
                    // it as inserted (for UX symmetry) but skip the write.
                    if (existingEntry.replacement == replacement) {
                        skipped++
                    } else {
                        manager.updateEntry(existingEntry.id, word, replacement)
                        inserted++
                    }
                }

                strategy == ConflictStrategy.KEEP_BOTH -> {
                    manager.addEntry(word, replacement)
                    inserted++
                }
            }
        }

        Logger.d(TAG, "Import done: inserted=$inserted skipped=$skipped " +
                "total=${dictionary.entries.size}")

        ImportReport(
            inserted = inserted,
            skippedDuplicates = skipped,
            total = dictionary.entries.size
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Export — serializes current dictionary to a String / Uri
    // ─────────────────────────────────────────────────────────────────

    /**
     * Build a JSON string for the given entries + optional metadata.
     * Pretty-printed (indent = 2) so humans can read and PR these files
     * on GitHub without running them through a formatter first.
     */
    fun buildJsonString(
        entries: List<PronunciationEntry>,
        title: String = "My Pronunciation Dictionary",
        description: String = "",
        language: String = "",
        author: String = ""
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_V1)
        root.put("title", title)
        if (description.isNotBlank()) root.put("description", description)
        if (language.isNotBlank()) root.put("language", language)
        if (author.isNotBlank()) root.put("author", author)
        root.put("createdAt", System.currentTimeMillis())

        val arr = JSONArray()
        // Sort alphabetically for deterministic output — makes diffs on GitHub
        // readable and avoids id-order leaking into the file.
        entries.sortedBy { it.word.lowercase() }.forEach { entry ->
            val e = JSONObject()
            e.put("word", entry.word)
            e.put("replacement", entry.replacement)
            arr.put(e)
        }
        root.put("entries", arr)

        // toString(2) = 2-space indent. Tradeoff is ~40% bigger files, but
        // these dictionaries are already tiny so readability wins.
        return root.toString(2)
    }

    /**
     * Write a JSON dictionary to a user-chosen Uri (from ACTION_CREATE_DOCUMENT).
     * Returns true on success.
     */
    suspend fun writeToUri(
        context: Context,
        uri: Uri,
        jsonString: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(jsonString.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            Logger.e(TAG, "writeToUri failed", e)
            false
        }
    }

    /**
     * Recommended default filename when the user exports. Includes a
     * yyyy-MM-dd stamp so users can keep multiple versions without
     * overwriting — the file picker will append (1), (2) etc. on collision
     * anyway but a date stamp is more human-readable.
     */
    fun suggestedFilename(): String {
        val stamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd",
            java.util.Locale.US
        ).format(java.util.Date())
        return "novelforge-pronunciation-$stamp.npd.json"
    }
}