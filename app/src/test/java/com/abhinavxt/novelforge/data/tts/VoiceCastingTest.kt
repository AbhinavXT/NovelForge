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

    private fun piece(quoted: Boolean, speaker: String?) = Dialogue.Piece("x", 0, 0, false, quoted, speaker)

    @Test
    fun castPicksTheVoiceForEachPiece() {
        val cast = VoiceCast(enabled = true, narrator = "2", otherDialogue = "5", characters = mapOf("Lin" to "7"))
        assertEquals("2", cast.voiceFor(piece(false, null)))
        assertEquals("7", cast.voiceFor(piece(true, "Lin")))
        assertEquals("5", cast.voiceFor(piece(true, "Chen")))
        assertEquals("2", cast.copy(enabled = false).voiceFor(piece(true, "Lin")))
    }

    @Test
    fun castsSavedWithSpeakerNumbersStillLoad() {
        val cast = VoiceCasting.parse("""{"enabled":true,"narrator":2,"otherDialogue":5,"characters":{"Lin":7,"Chen":3}}""")
        assertEquals(true, cast.enabled)
        assertEquals("2", cast.narrator)
        assertEquals("5", cast.otherDialogue)
        assertEquals(mapOf("Lin" to "7", "Chen" to "3"), cast.characters)
        assertEquals("7", cast.voiceFor(piece(true, "Lin")))
        // Nothing stored, or garbage, is an empty cast.
        assertEquals(VoiceCast(), VoiceCasting.parse(null))
        assertEquals(VoiceCast(), VoiceCasting.parse("not json"))
        assertEquals("0", VoiceCasting.parse("""{"enabled":true}""").narrator)
    }

    @Test
    fun systemVoiceAndPiperModelAssignmentsRoundTrip() {
        val google = VoiceCast(
            enabled = true, narrator = "en-us-x-sfg-local", otherDialogue = "en-gb-x-gbb-local",
            characters = mapOf("Lin" to "en-us-x-tpd-network")
        )
        assertEquals(google, VoiceCasting.parse(VoiceCasting.toJson(google)))
        val piper = VoiceCast(enabled = true, characters = mapOf("Chen" to "vits-piper-en_US-lessac-low"))
        assertEquals(piper, VoiceCasting.parse(VoiceCasting.toJson(piper)))
    }

    @Test
    fun storageKeysNeverCollideAcrossKinds() {
        val keys = setOf(
            "kokoro-en-v0_19", VoiceCasting.piperKey("kokoro-en-v0_19"),
            VoiceCasting.googleKey("en"), VoiceCasting.googleKey("fr")
        )
        assertEquals(4, keys.size)
    }

    @Test
    fun aVoiceThatIsGoneReadsAsTheNarrator() {
        val cast = VoiceCast(
            enabled = true, narrator = "old-narrator", otherDialogue = "gone",
            characters = mapOf("Lin" to "voice-a", "Chen" to "gone")
        ).restrictedTo(setOf("voice-a", "voice-b", "narrator-now"), "narrator-now")
        assertEquals("narrator-now", cast.voiceFor(piece(false, null)))
        assertEquals("voice-a", cast.voiceFor(piece(true, "Lin")))
        assertEquals("narrator-now", cast.voiceFor(piece(true, "Chen")))
        assertEquals("narrator-now", cast.voiceFor(piece(true, "Nobody")))
    }

    @Test
    fun fewerThanTwoVoicesCastsNobody() {
        val one = listOf(CastVoice("a", "A"))
        assertEquals(false, CastVoices(CastKind.GOOGLE_VOICE, "k", one, "a", false).usable)
        assertEquals(false, CastVoices(CastKind.GOOGLE_VOICE, "k", emptyList(), "a", false).usable)
        assertEquals(true, CastVoices(CastKind.GOOGLE_VOICE, "k", one + CastVoice("b", "B"), "a", false).usable)
        assertEquals(emptyMap<String, String>(), Speakers.autoCastVoices(listOf("Lin"), emptyMap(), one, "a"))
    }

    @Test
    fun autoCastWorksForNamedVoicesAndMatchesGender() {
        val voices = listOf(
            CastVoice("n", "Narrator", true),
            CastVoice("f1", "F1", true), CastVoice("m1", "M1", false), CastVoice("x", "Unknown", null)
        )
        val cast = Speakers.autoCastVoices(listOf("Lin", "Chen"), mapOf("Lin" to true, "Chen" to false), voices, "n")
        assertEquals("f1", cast["Lin"])
        assertEquals("m1", cast["Chen"])
        assert("n" !in cast.values)
    }

    @Test
    fun libriTtsSpeakersAreNumberedAndCapped() {
        val speakers = Speakers.forModel("vits-piper-en_US-libritts_r-medium", 904)
        assertEquals(100, speakers.size)
        assertEquals("Speaker 3", speakers[2].label)
        assertEquals(3, Speakers.forModel("vits-piper-en_US-libritts_r-medium", 3).size)
    }

    @Test
    fun systemVoicesAreSameLanguageInstalledAndOfflineFirst() {
        val all = listOf(
            SystemVoice("en-us-x-b-network", "en", installed = true, needsNetwork = true),
            SystemVoice("en-us-x-a-local", "en", installed = true, needsNetwork = false),
            SystemVoice("en-gb-x-c-local", "en", installed = false, needsNetwork = false),
            SystemVoice("fr-fr-x-d-local", "fr", installed = true, needsNetwork = false)
        )
        assertEquals(listOf("en-us-x-a-local", "en-us-x-b-network"), SystemVoices.castable(all, "en").map { it.name })
    }

    @Test
    fun piperVoicesOfTheNarratorsLanguageStandIn() {
        val downloaded = listOf(
            "vits-piper-en_US-amy-low", "vits-piper-en_GB-alan-low", "vits-piper-de_DE-thorsten-low",
            "vits-piper-en_US-libritts_r-medium", "kokoro-en-v0_19"
        )
        assertEquals(
            listOf("vits-piper-en_US-amy-low", "vits-piper-en_GB-alan-low"),
            PiperVoices.sameLanguage("vits-piper-en_US-amy-low", downloaded)
        )
        assertEquals(emptyList<String>(), PiperVoices.sameLanguage("kokoro-en-v0_19", downloaded))
        assertEquals("en", PiperVoices.languageOf("vits-piper-en_US-amy-low"))
        assertEquals(true, PiperVoices.genderOf("Amy (US, Low) \u2640"))
        assertNull(PiperVoices.genderOf("LibriTTS-R (US, Medium, multi-speaker)"))
    }

    @Test fun pickLabelNamesSpeakersBeyondTheCap() {
        val listed = Speakers.forModel("vits-libritts_r-medium", 900).map { it.toCastVoice() }
        assertEquals("Speaker 3", Speakers.pickLabel(listed, "2"))
        assertEquals("Speaker 451", Speakers.pickLabel(listed, "450"))
        assertEquals("Narrator's voice", Speakers.pickLabel(listed, null))
        assertEquals("Narrator's voice", Speakers.pickLabel(listed, "some-model"))
        assertEquals("Speaker 451", Speakers.labelFor("vits-libritts_r-medium", 450).label)
    }
}
