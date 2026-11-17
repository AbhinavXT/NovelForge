package com.abhinavxt.novelforge.data.tts

/**
 * Keeps the most recently used values of an id-keyed set, at most [capacity]; the least recently used
 * is passed to [onEvict] (to release it) when a new one doesn't fit. Used to hold the extra Piper models
 * loaded for character voices.
 */
class ModelLru<T : Any>(private val capacity: Int, private val onEvict: (String, T) -> Unit = { _, _ -> }) {
    private val map = LinkedHashMap<String, T>(8, 0.75f, true)

    @Synchronized
    fun get(id: String): T? = map[id]

    @Synchronized
    fun put(id: String, value: T) {
        map.remove(id)?.let { if (it !== value) onEvict(id, it) }
        map[id] = value
        while (map.size > capacity) {
            val eldest = map.entries.first()
            map.remove(eldest.key)
            onEvict(eldest.key, eldest.value)
        }
    }

    @Synchronized
    fun ids(): List<String> = map.keys.toList()

    /** Empties the set WITHOUT calling [onEvict]; the caller releases the returned values when it is safe. */
    @Synchronized
    fun takeAll(): List<T> {
        val all = map.values.toList()
        map.clear()
        return all
    }

    @Synchronized
    fun clear() {
        val all = map.entries.map { it.key to it.value }
        map.clear()
        all.forEach { (id, v) -> onEvict(id, v) }
    }
}

/** Remembers which ids failed to load, so a broken one is not retried for every line. */
class FailedLoads {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun markFailed(id: String) { ids.add(id) }
    fun hasFailed(id: String) = id in ids
    fun clear() = ids.clear()
}

/** Sample-rate conversion for joining audio from voices that differ (Piper models use 16000 and 22050). */
object Resample {

    /** [samples] at [from] Hz as samples at [to] Hz, by linear interpolation. */
    fun linear(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0 || to <= 0 || samples.isEmpty()) return samples
        val outSize = maxOf(1, ((samples.size.toLong() * to) / from).toInt())
        val step = from.toDouble() / to
        return FloatArray(outSize) { i ->
            val pos = i * step
            val lo = pos.toInt().coerceAtMost(samples.lastIndex)
            val hi = (lo + 1).coerceAtMost(samples.lastIndex)
            val frac = (pos - lo).toFloat()
            samples[lo] + (samples[hi] - samples[lo]) * frac
        }
    }

    /** Little-endian 16-bit mono PCM bytes as floats in -1..1. */
    fun pcm16ToFloat(bytes: ByteArray, length: Int = bytes.size): FloatArray =
        FloatArray(length / 2) { i ->
            val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            v.toShort() / 32768f
        }
}

/** Which downloaded Piper voices can stand in for one another as character voices. */
object PiperVoices {
    private const val PREFIX = "vits-piper-"

    /** "vits-piper-en_US-amy-low" is English ("en"); null for anything that isn't a Piper folder. */
    fun languageOf(modelId: String): String? {
        if (!modelId.startsWith(PREFIX)) return null
        return modelId.removePrefix(PREFIX).substringBefore('-').substringBefore('_').ifBlank { null }
    }

    /** Multi-speaker Piper models are cast by speaker number instead. */
    fun isMultiSpeaker(modelId: String) = modelId.contains("libritts", ignoreCase = true)

    /** The voices of [downloaded] that speak [narratorModel]'s language, the narrator's own included. */
    fun sameLanguage(narratorModel: String, downloaded: List<String>): List<String> {
        val lang = languageOf(narratorModel) ?: return emptyList()
        return downloaded.filter { languageOf(it) == lang && !isMultiSpeaker(it) }
            .let { if (narratorModel in it) it else listOf(narratorModel) + it }
            .distinct()
    }

    /** Female/male from the catalogue's glyph in a name like "Amy (US, Low) ♀"; null when it has none. */
    fun genderOf(catalogName: String?): Boolean? = when {
        catalogName == null -> null
        '♀' in catalogName -> true
        '♂' in catalogName -> false
        else -> null
    }
}

/** A system TTS voice reduced to what choosing character voices needs (so the rules can be tested). */
data class SystemVoice(val name: String, val language: String, val installed: Boolean, val needsNetwork: Boolean)

object SystemVoices {
    /** Installed voices of [language], ones that work offline first. */
    fun castable(all: List<SystemVoice>, language: String): List<SystemVoice> =
        all.filter { it.language == language && it.installed }
            .sortedWith(compareBy<SystemVoice> { it.needsNetwork }.thenBy { it.name })
}
