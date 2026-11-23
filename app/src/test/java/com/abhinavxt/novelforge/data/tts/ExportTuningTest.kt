package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportTuningTest {
    @Test fun fastCores_countsAboveMinimum() {
        // 4 little + 3 big + 1 prime
        assertEquals(4, fastCoreCount(listOf(1800L, 1800, 1800, 1800, 2400, 2400, 2400, 3000)))
    }

    @Test fun fastCores_uniformCoresAllCount() = assertEquals(4, fastCoreCount(listOf(2000L, 2000, 2000, 2000)))

    @Test fun fastCores_empty() = assertEquals(0, fastCoreCount(emptyList()))

    @Test fun exportThreads_cappedAtSix() =
        assertEquals(6, exportThreadCount(List(8) { 2000L }, 8, playbackThreads = 4))

    @Test fun exportThreads_neverBelowPlayback() =
        assertEquals(4, exportThreadCount(listOf(1000L, 1000, 1000, 2000), 4, playbackThreads = 4))

    @Test fun exportThreads_fallsBackToHalfCores() {
        assertEquals(4, exportThreadCount(emptyList(), 8, playbackThreads = 2))
        assertEquals(2, exportThreadCount(emptyList(), 2, playbackThreads = 2))
    }

    private fun s(t: String, para: Boolean = false, v: String? = null) = SentenceEntry(t, para, v)

    @Test fun chunks_groupSameVoiceAndSplitOnVoiceChange() {
        val c = chunkSentences(listOf(s("A.", v = "1"), s("B.", v = "1"), s("C.", v = "2")), 100)
        assertEquals(listOf("A. B." to 2, "C." to 1), c.map { it.text to it.sentences })
        assertEquals(listOf("1", "2"), c.map { it.voice })
    }

    @Test fun chunks_breakAtParagraphEnd() {
        val c = chunkSentences(listOf(s("A."), s("B.", para = true), s("C.")), 100)
        assertEquals(listOf("A. B.", "C."), c.map { it.text })
        assertEquals(listOf(true, false), c.map { it.isParagraphEnd })
    }

    @Test fun chunks_respectLengthLimit() {
        val c = chunkSentences(listOf(s("aaaa"), s("bbbb"), s("cccc")), 9)
        assertEquals(listOf("aaaa bbbb", "cccc"), c.map { it.text })
    }

    @Test fun chunks_longSentenceSplitsByWordsAndCountsOnce() {
        val c = chunkSentences(listOf(s("aaa bbb ccc ddd", para = true)), 8)
        assertEquals(listOf("aaa bbb", "ccc ddd"), c.map { it.text })
        assertEquals(1, c.sumOf { it.sentences })
        assertEquals(listOf(false, true), c.map { it.isParagraphEnd })
    }
}
