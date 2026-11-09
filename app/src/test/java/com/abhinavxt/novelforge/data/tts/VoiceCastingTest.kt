package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCastingTest {

    @Test
    fun kokoroSpeakersAreNamedAndOthersNumbered() {
        val en = Speakers.forModel("kokoro-en-v0_19", 11)
        assertEquals("Bella · American female", en[1].label)
        assertEquals(false, en[10].female)
        assertEquals(103, Speakers.forModel("kokoro-multi-lang-v1_1-int8", 103).size)
        assertEquals("Vale · British female", Speakers.forModel("kokoro-multi-lang-v1_1", 103)[2].label)
        val unknown = Speakers.forModel("vits-something", 3)
        assertEquals("Voice 2", unknown[1].label)
        assertNull(unknown[1].female)
    }

    @Test
    fun autoCastMatchesGenderAndAccentAndLeavesTheNarratorAlone() {
        val speakers = Speakers.forModel("kokoro-en-v0_19", 11)
        val cast = Speakers.autoCast(
            listOf("Lin", "Chen", "Mei", "Wang"),
            mapOf("Lin" to true, "Chen" to false, "Mei" to true),
            speakers,
            narrator = 0
        )
        // American voices only (the narrator's accent), never the narrator's own.
        assertEquals(setOf('a'), cast.values.map { speakers[it].accent }.toSet())
        assertEquals(true, speakers[cast.getValue("Lin")].female)
        assertEquals(false, speakers[cast.getValue("Chen")].female)
        assertEquals(true, speakers[cast.getValue("Mei")].female)
        assert(cast.getValue("Lin") != cast.getValue("Mei"))
        assert(0 !in cast.values)
    }

    @Test
    fun aSingleSpeakerModelCastsNobody() {
        assertEquals(emptyMap<String, Int>(), Speakers.autoCast(listOf("Lin"), emptyMap(), Speakers.forModel("piper", 1), 0))
    }

    @Test
    fun genderComesFromGlossaryNotes() {
        assertEquals(true, Speakers.genderFromNote("female, she/her"))
        assertEquals(false, Speakers.genderFromNote("Male, he/him"))
        assertNull(Speakers.genderFromNote("sect elder"))
    }

    @Test
    fun castPicksTheVoiceForEachPiece() {
        val cast = VoiceCast(enabled = true, narrator = 2, otherDialogue = 5, characters = mapOf("Lin" to 7))
        fun piece(quoted: Boolean, speaker: String?) = Dialogue.Piece("x", 0, 0, false, quoted, speaker)
        assertEquals(2, cast.voiceFor(piece(false, null)))
        assertEquals(7, cast.voiceFor(piece(true, "Lin")))
        assertEquals(5, cast.voiceFor(piece(true, "Chen")))
        assertEquals(2, cast.copy(enabled = false).voiceFor(piece(true, "Lin")))
    }
}
