package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAudioNameTest {
    @Test fun sanitizesLikeTheExportedFolderNames() {
        assertEquals("Le_voyage_de_Lucie", AudioExporter.sanitizeName("Le voyage de Lucie"))
        assertEquals("Piper__Amy__Low__en-US_", AudioExporter.sanitizeName("Piper: Amy (Low, en-US)"))
    }

    @Test fun isIdempotentSoFolderNamesCanBePassedBack() {
        val once = AudioExporter.sanitizeName("A: b  c")
        assertEquals(once, AudioExporter.sanitizeName(once))
    }
}
