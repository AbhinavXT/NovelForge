package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterFilePickTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pick(voice: String?, vararg names: String) =
        M4BAudiobookBuilder.pickChapterFiles(names.map { tmp.newFile(it) }, voice).map { it.name }

    @Test fun wavWinsWhenBothFormatsExist() {
        assertEquals(listOf("Chapter_1_A.wav", "Chapter_2_A.m4a"),
            pick(null, "Chapter_1_A.wav", "Chapter_1_A.m4a", "Chapter_2_A.m4a"))
    }

    @Test fun sortsNumericallyAcrossFormatsAndSkipsOthers() {
        assertEquals(listOf("Chapter_2_A.m4a", "Chapter_10_A.wav"),
            pick(null, "Chapter_10_A.wav", "Chapter_2_A.m4a", "Chapter_3_A.mp3", "Novel_audiobook.m4b", "X_audiobook.m4a"))
    }

    @Test fun voiceFilterApplies() {
        assertEquals(listOf("Chapter_1_B.m4a"), pick("B", "Chapter_1_A.wav", "Chapter_1_B.m4a"))
    }
}
