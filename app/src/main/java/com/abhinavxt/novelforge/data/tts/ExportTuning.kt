package com.abhinavxt.novelforge.data.tts

/** Pure helpers that make audio export faster; kept free of Android types so they unit-test. */

/** Cores faster than the slowest one (big/prime cluster); all of them when every core runs at one speed. */
internal fun fastCoreCount(maxFreqs: List<Long>): Int {
    val min = maxFreqs.minOrNull() ?: return 0
    return maxFreqs.count { it > min }.takeIf { it > 0 } ?: maxFreqs.size
}

/** Threads for an export: the fast cores, capped, never below what playback uses. Unreadable freqs: half the cores. */
internal fun exportThreadCount(maxFreqs: List<Long>, cores: Int, playbackThreads: Int, cap: Int = 6): Int {
    val fast = if (maxFreqs.isEmpty()) maxOf(2, cores / 2) else fastCoreCount(maxFreqs)
    return fast.coerceAtMost(cap).coerceAtLeast(playbackThreads)
}

/** Reloading the model only pays off when it adds threads. */
internal fun shouldBoost(exportThreads: Int, playbackThreads: Int) = exportThreads > playbackThreads

internal data class SentenceEntry(
    val text: String,
    val isParagraphEnd: Boolean,
    /** The cast's voice for this line (speaker number, Piper model or system voice), or null for the export's own. */
    val voice: String? = null
)

/** One synthesis call covering [sentences] sentences; a sentence cut into several calls counts on the last only. */
internal data class SpeechChunk(
    val text: String,
    val voice: String?,
    val isParagraphEnd: Boolean,
    val sentences: Int
)

/**
 * Groups consecutive sentences of one voice into chunks of at most [maxLen] chars. A voice change or a
 * paragraph end closes the chunk; a sentence longer than [maxLen] is cut at word boundaries. A non-zero
 * [sentencePauseMs] closes every chunk at its sentence, because the exporter inserts that silence between chunks.
 */
internal fun chunkSentences(entries: List<SentenceEntry>, maxLen: Int, sentencePauseMs: Int = 0): List<SpeechChunk> {
    val out = ArrayList<SpeechChunk>()
    val cur = StringBuilder()
    var curVoice: String? = null
    var count = 0
    var paragraphEnd = false

    fun flush() {
        if (count > 0) out += SpeechChunk(cur.toString(), curVoice, paragraphEnd, count)
        cur.clear(); count = 0; paragraphEnd = false
    }

    for (e in entries) {
        if (count > 0 && (e.voice != curVoice || cur.length + 1 + e.text.length > maxLen)) flush()
        if (e.text.length > maxLen) {
            val parts = splitByWords(e.text, maxLen)
            parts.forEachIndexed { i, part ->
                val last = i == parts.lastIndex
                out += SpeechChunk(part, e.voice, last && e.isParagraphEnd, if (last) 1 else 0)
            }
            continue
        }
        if (count > 0) cur.append(' ')
        cur.append(e.text)
        curVoice = e.voice
        count++
        paragraphEnd = e.isParagraphEnd
        if (e.isParagraphEnd || sentencePauseMs > 0) flush()
    }
    flush()
    return out
}

private fun splitByWords(text: String, maxLen: Int): List<String> {
    val parts = ArrayList<String>()
    val cur = StringBuilder()
    for (word in text.split(' ')) {
        if (word.isEmpty()) continue
        if (cur.isNotEmpty() && cur.length + 1 + word.length > maxLen) { parts += cur.toString(); cur.clear() }
        if (word.length > maxLen) {
            word.chunked(maxLen).forEach { parts += it }  // a single word over the limit: hard cut
            continue
        }
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(word)
    }
    if (cur.isNotEmpty()) parts += cur.toString()
    return parts
}
