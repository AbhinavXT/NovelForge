package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A single definition entry with part of speech, meaning, and optional example.
 */
data class DictionaryDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null
)

/**
 * Complete dictionary result for a looked-up word.
 */
data class DictionaryResult(
    val word: String,
    val phonetic: String?,
    val definitions: List<DictionaryDefinition>,
    val language: String? = null
)

sealed interface DictionaryState {
    object Idle : DictionaryState
    object Loading : DictionaryState
    data class Success(val result: DictionaryResult) : DictionaryState
    data class NotFound(val word: String) : DictionaryState
    data class Error(val message: String) : DictionaryState
}

/**
 * Multilingual dictionary client.
 *
 * - English: uses dictionaryapi.dev (better formatting, free, no key)
 * - All other languages: uses en.wiktionary.org REST API,
 *   which provides English definitions for words in 170+ languages.
 */
object DictionaryRepository {

    private const val DICT_API_BASE = "https://api.dictionaryapi.dev/api/v2/entries/en"
    private const val WIKTIONARY_BASE = "https://en.wiktionary.org/api/rest_v1/page/definition"

    /**
     * Map of DictionaryLanguage codes to the language name used in Wiktionary's response.
     * Wiktionary groups definitions by language name (e.g. "French", "Hindi").
     */
    private val wiktionaryLanguageNames = mapOf(
        "hi" to "Hindi",
        "ja" to "Japanese",
        "zh" to "Chinese",
        "fr" to "French",
        "es" to "Spanish"
    )

    /**
     * Looks up a word and returns a [DictionaryResult], or null on failure/not-found.
     * Routes to the appropriate API based on language code.
     */
    suspend fun lookup(word: String, languageCode: String = "en"): DictionaryResult? =
        withContext(Dispatchers.IO) {
            val cleanWord = word.trim()
                .replace(Regex("[\\s]+"), " ") // Collapse whitespace

            if (cleanWord.isBlank()) return@withContext null

            if (languageCode == "en") {
                lookupEnglish(cleanWord)
            } else {
                lookupWiktionary(cleanWord, languageCode)
            }
        }

    // ── English: dictionaryapi.dev ───────────────────────────────

    private fun lookupEnglish(word: String): DictionaryResult? {
        // Strip non-alpha characters for English (keep apostrophes and hyphens)
        val cleanWord = word.lowercase().replace(Regex("[^a-zA-Z'-]"), "")
        if (cleanWord.isBlank()) return null

        val encoded = URLEncoder.encode(cleanWord, "UTF-8")
        val url = URL("$DICT_API_BASE/$encoded")

        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Logger.d("Dictionary", "dictionaryapi.dev returned $responseCode for '$cleanWord'")
                connection.disconnect()
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseEnglishResponse(cleanWord, responseBody)
        } catch (e: Exception) {
            Logger.e("Dictionary", "English lookup failed for '$cleanWord'", e)
            null
        }
    }

    private fun parseEnglishResponse(word: String, json: String): DictionaryResult? {
        return try {
            val array = JSONArray(json)
            if (array.length() == 0) return null

            val entry = array.getJSONObject(0)

            val phonetic = entry.optString("phonetic", null)
                ?: entry.optJSONArray("phonetics")?.let { phonetics ->
                    (0 until phonetics.length())
                        .map { phonetics.getJSONObject(it) }
                        .firstOrNull { it.optString("text", "").isNotBlank() }
                        ?.optString("text")
                }

            val definitions = mutableListOf<DictionaryDefinition>()
            val meanings = entry.optJSONArray("meanings") ?: return null

            for (i in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(i)
                val partOfSpeech = meaning.optString("partOfSpeech", "")
                val defs = meaning.optJSONArray("definitions") ?: continue

                val limit = minOf(2, defs.length())
                for (j in 0 until limit) {
                    val def = defs.getJSONObject(j)
                    definitions.add(
                        DictionaryDefinition(
                            partOfSpeech = partOfSpeech,
                            definition = def.optString("definition", ""),
                            example = def.optString("example", null)
                                ?.takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
                if (definitions.size >= 6) break
            }

            if (definitions.isEmpty()) return null

            DictionaryResult(
                word = entry.optString("word", word),
                phonetic = phonetic?.takeIf { it.isNotBlank() },
                definitions = definitions,
                language = "English"
            )
        } catch (e: Exception) {
            Logger.e("Dictionary", "English parse error", e)
            null
        }
    }

    // ── Non-English: Wiktionary REST API ─────────────────────────

    private fun lookupWiktionary(word: String, languageCode: String): DictionaryResult? {
        val encoded = URLEncoder.encode(word, "UTF-8")
        val url = URL("$WIKTIONARY_BASE/$encoded")

        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            // Wiktionary REST API requires a User-Agent
            connection.setRequestProperty("User-Agent", "NovelReader/1.5 (Android)")
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Logger.d("Dictionary", "Wiktionary returned $responseCode for '$word' ($languageCode)")
                connection.disconnect()
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseWiktionaryResponse(word, languageCode, responseBody)
        } catch (e: Exception) {
            Logger.e("Dictionary", "Wiktionary lookup failed for '$word'", e)
            null
        }
    }

    /**
     * Wiktionary response is a JSON object where keys are language names
     * (e.g. "French", "Hindi") and values are arrays of part-of-speech blocks.
     * We filter to the target language.
     */
    private fun parseWiktionaryResponse(
        word: String,
        languageCode: String,
        json: String
    ): DictionaryResult? {
        return try {
            val root = JSONObject(json)
            val targetLanguageName = wiktionaryLanguageNames[languageCode]

            // Find the right language section
            // Try exact match first, then case-insensitive search
            val languageKey = if (targetLanguageName != null && root.has(targetLanguageName)) {
                targetLanguageName
            } else {
                // Fallback: search keys case-insensitively or take first available
                root.keys().asSequence().firstOrNull { key ->
                    targetLanguageName != null && key.equals(targetLanguageName, ignoreCase = true)
                } ?: root.keys().asSequence().firstOrNull()
            }

            if (languageKey == null) return null

            val languageEntries = root.optJSONArray(languageKey) ?: return null

            val definitions = mutableListOf<DictionaryDefinition>()

            for (i in 0 until languageEntries.length()) {
                val entry = languageEntries.getJSONObject(i)
                val partOfSpeech = entry.optString("partOfSpeech", "")
                    .replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
                val defs = entry.optJSONArray("definitions") ?: continue

                val limit = minOf(2, defs.length())
                for (j in 0 until limit) {
                    val def = defs.getJSONObject(j)
                    val definitionHtml = def.optString("definition", "")
                    val definitionText = stripHtml(definitionHtml)

                    if (definitionText.isBlank()) continue

                    // Wiktionary examples can be in "parsedExamples" or "examples" arrays
                    val example = extractWiktionaryExample(def)

                    definitions.add(
                        DictionaryDefinition(
                            partOfSpeech = partOfSpeech,
                            definition = definitionText,
                            example = example
                        )
                    )
                }
                if (definitions.size >= 6) break
            }

            if (definitions.isEmpty()) return null

            DictionaryResult(
                word = word,
                phonetic = null, // Wiktionary REST API doesn't return phonetics directly
                definitions = definitions,
                language = languageKey
            )
        } catch (e: Exception) {
            Logger.e("Dictionary", "Wiktionary parse error", e)
            null
        }
    }

    /**
     * Extract example sentence from a Wiktionary definition object.
     * Checks "parsedExamples" first, then "examples".
     */
    private fun extractWiktionaryExample(def: JSONObject): String? {
        // Try parsedExamples (structured)
        val parsedExamples = def.optJSONArray("parsedExamples")
        if (parsedExamples != null && parsedExamples.length() > 0) {
            val example = parsedExamples.getJSONObject(0)
            val text = example.optString("example", "")
            val clean = stripHtml(text).trim()
            if (clean.isNotBlank()) return clean
        }

        // Try examples (plain array of strings)
        val examples = def.optJSONArray("examples")
        if (examples != null && examples.length() > 0) {
            val text = examples.optString(0, "")
            val clean = stripHtml(text).trim()
            if (clean.isNotBlank()) return clean
        }

        return null
    }

    /**
     * Strip HTML tags from Wiktionary definition text.
     * Handles common patterns like <a href="...">text</a>, <b>, <i>, <span>, etc.
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "") // Remove all HTML tags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}