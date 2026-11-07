package com.abhinavxt.novelforge.data

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelforge.data.epub.DocumentFormat
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The imported offline dictionaries. Each lives in its own folder under
 * filesDir/dictionaries, which is left out of Android's cloud backup: one
 * dictionary can exceed its 25 MB quota, and then nothing gets backed up.
 */
class StarDictManager(private val context: Context) {

    private val root = File(context.filesDir, "dictionaries")

    private val _dictionaries = MutableStateFlow<List<StarDict>>(emptyList())
    val dictionaries: StateFlow<List<StarDict>> = _dictionaries.asStateFlow()

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        val folders = root.listFiles().orEmpty()
        folders.filter { it.name.startsWith(IMPORT_PREFIX) }.forEach { it.deleteRecursively() }
        _dictionaries.value = folders.filterNot { it.name.startsWith(IMPORT_PREFIX) }.mapNotNull { folder ->
            runCatching { StarDict(folder) }
                .onFailure { Logger.e("Dictionary", "Skipping ${folder.name}", it) }
                .getOrNull()
        }.sortedBy { it.info.name }
    }

    /**
     * Import what the user picked: one archive, or the dictionary's files.
     * @return the dictionary's name
     * @throws StarDictException with a message to show
     */
    suspend fun import(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val staging = File(root, IMPORT_PREFIX + UUID.randomUUID()).apply { mkdirs() }
        try {
            uris.forEach { uri ->
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw StarDictException("Couldn't open that file.")
                input.buffered().use { StarDict.unpack(DocumentFormat.displayName(context, uri), it, staging) }
            }
            val name = StarDict.finish(staging).info.name
            if (_dictionaries.value.any { it.info.name == name }) {
                throw StarDictException("\"$name\" is already added.")
            }
            val folder = File(root, UUID.randomUUID().toString())
            if (!staging.renameTo(folder)) throw StarDictException("Couldn't save that dictionary.")
            _dictionaries.update { list -> (list + StarDict(folder)).sortedBy { it.info.name } }
            name
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun delete(dictionary: StarDict) = withContext(Dispatchers.IO) {
        _dictionaries.update { list -> list - dictionary }
        dictionary.dir.deleteRecursively()
    }

    /** [word] in every dictionary, each headed by its name, or null if none has it. */
    suspend fun lookup(word: String): DictionaryResult? = withContext(Dispatchers.IO) {
        val clean = StarDict.cleanWord(word)
        if (clean.isEmpty()) return@withContext null
        val hits = _dictionaries.value.flatMap { dictionary ->
            runCatching { dictionary.lookup(clean) }
                .onFailure { Logger.e("Dictionary", "Lookup failed in ${dictionary.info.name}", it) }
                .getOrDefault(emptyList())
                .map { dictionary.info.name to it }
        }
        if (hits.isEmpty()) return@withContext null
        DictionaryResult(
            word = hits.first().second.word,
            phonetic = hits.firstNotNullOfOrNull { it.second.phonetic },
            definitions = hits.map { (name, entry) -> DictionaryDefinition(name, entry.text) }
        )
    }

    private companion object {
        const val IMPORT_PREFIX = ".import-"
    }
}
