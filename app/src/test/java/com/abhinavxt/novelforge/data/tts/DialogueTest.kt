package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogueTest {

    private val names = listOf("Li Wei", "Chen", "Lin")

    private fun voiced(text: String) =
        Dialogue.pieces(text, names, voiced = true).map { Triple(it.text, it.quoted, it.speaker) }

    @Test
    fun withoutVoicesItIsOnePiecePerSentenceAsBefore() {
        val pieces = Dialogue.pieces("\"Go,\" said Li Wei. He left.\n\nNext one.")
        assertEquals(listOf("\"Go,\" said Li Wei.", "He left.", "Next one."), pieces.map { it.text })
        assertEquals(listOf(0, 1, 0), pieces.map { it.sentence })
        assertEquals(listOf(false, true, true), pieces.map { it.paragraphEnd })
    }

    @Test
    fun aQuoteAndItsTagAreSplitAndTheTagNamesTheSpeaker() {
        assertEquals(
            listOf(Triple("“Go,”", true, "Li Wei"), Triple("said Li Wei.", false, null)),
            voiced("“Go,” said Li Wei.")
        )
        assertEquals("Chen", voiced("Chen asked, \"Why?\"").last().third)
    }

    @Test
    fun aQuoteRunningAcrossSentencesStaysQuotedAndKeepsSentenceIndexes() {
        val pieces = Dialogue.pieces("Lin turned. “Wait. Don’t go.”", names, voiced = true)
        assertEquals(listOf("Lin turned.", "“Wait.", "Don’t go.”"), pieces.map { it.text })
        assertEquals(listOf(false, true, true), pieces.map { it.quoted })
        assertEquals(listOf(0, 1, 2), pieces.map { it.sentence })
        // No speech verb, but Lin is the only one named.
        assertEquals("Lin", pieces[1].speaker)
    }

    @Test
    fun bareLinesInABackAndForthAlternate() {
        val text = "\"Who are you?\" Chen asked.\n\n\"Li Wei,\" said Li Wei.\n\n\"Why come here?\"\n\n\"To find you.\""
        val speakers = Dialogue.pieces(text, names, voiced = true).filter { it.quoted }.map { it.speaker }
        assertEquals(listOf("Chen", "Li Wei", "Chen", "Li Wei"), speakers)
    }

    @Test
    fun anUnknownSpeakerIsNull() {
        assertEquals(null, voiced("“Run!” someone shouted.").first().third)
        // Two characters and no verb: can't tell.
        assertEquals(null, voiced("Chen looked at Lin. “Well?”").last().third)
    }

    @Test
    fun punctuationLeftOverFromAQuoteIsDropped() {
        assertEquals(listOf("“Hello”"), voiced("“Hello”.").map { it.first })
    }
}
