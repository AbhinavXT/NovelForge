package com.abhinavxt.novelforge.data.source.plugin

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class CreditStripperTest {

    private fun text(html: String) = Jsoup.parse(CreditStripper.strip(html)).select("p").map { it.text() }

    @Test
    fun creditLinesAndAuthorNotesGoTheStoryStays() {
        val html = """
            <p>Translator: Foo  Editor: Bar</p>
            <p><strong>Edited by: Baz</strong></p>
            <p>The editor said: "no".</p>
            <p>He went home.</p>
            <div class="qnauthornotecontainer"><p>Thanks for reading!</p></div>
        """
        assertEquals(listOf("The editor said: \"no\".", "He went home."), text(html))
    }

    @Test
    fun aLongParagraphThatStartsLikeACreditIsKept() {
        val long = "Translator: " + "word ".repeat(60)
        assertEquals(listOf(long.trim()), text("<p>$long</p>"))
    }
}
