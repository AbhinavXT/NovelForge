package com.abhinavxt.novelforge.data.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationTextTest {

    @Test
    fun glossaryMatchesCjkNamesFlushAgainstOtherCharacters() {
        val terms = listOf(Term("李伟", "Li Wei"), Term("李", "Lee"))
        assertEquals("Li Wei说，Lee", Glossary.apply("李伟说，李", terms))
    }

    @Test
    fun glossaryMatchesLatinTermsAsWholeWordsInAnyCase() {
        val terms = listOf(Term("Xiao Yan", "Xiao Yan"), Term("Yan", "Flame"), Term("Li", "Lee"))
        assertEquals("Xiao Yan met Flame and Lisa, not Lee.", Glossary.apply("xiao yan met Yan and Lisa, not li.", terms))
    }

    @Test
    fun relevantTermsAreTheOnesTheTextMentions() {
        val terms = listOf(Term("李伟", "Li Wei"), Term("王", "Wang"))
        assertEquals(listOf(Term("李伟", "Li Wei")), Glossary.relevant("李伟走了", terms))
    }

    @Test
    fun languageComesFromTheScript() {
        assertEquals("zh", TranslationText.detectLanguage("他走进了房间。"))
        assertEquals("ja", TranslationText.detectLanguage("彼は部屋に入った。"))
        assertEquals("ko", TranslationText.detectLanguage("그는 방으로 들어갔다."))
        assertNull(TranslationText.detectLanguage("He walked into the room, Li Wei said."))
    }

    @Test
    fun batchesStayUnderTheLimitAndKeepOrder() {
        val paragraphs = listOf("aaaa", "bbbb", "cccc", "dddddddddddd", "ee")
        assertEquals(listOf(0..1, 2..2, 3..3, 4..4), TranslationText.batches(paragraphs, 9))
        assertEquals(emptyList<IntRange>(), TranslationText.batches(emptyList(), 9))
    }

    @Test
    fun cleanKeepsOneParagraphAndFallsBackToTheOriginal() {
        assertEquals("One two", TranslationText.clean(" One\n\n two ", "x"))
        assertEquals("原文", TranslationText.clean("  ", "原文"))
    }

    @Test
    fun replyIsReadThroughCodeFencesWithItsNewTerms() {
        val reply = """
            ```json
            {"paragraphs": ["He came.", "She left."], "new_terms": [{"source": "林", "target": "Lin", "note": "female, she/her"}, {"source": "", "target": "x"}]}
            ```
        """.trimIndent()
        val parsed = TranslationText.parseReply(reply, 2)!!
        assertEquals(listOf("He came.", "She left."), parsed.paragraphs)
        assertEquals(listOf(Term("林", "Lin", "female, she/her")), parsed.newTerms)
    }

    @Test
    fun replyWithTheWrongCountOrNoJsonIsRejected() {
        assertNull(TranslationText.parseReply("""{"paragraphs": ["only one"]}""", 2))
        assertNull(TranslationText.parseReply("Sorry, I can't.", 1))
        assertEquals(emptyList<Term>(), TranslationText.parseReply("""{"paragraphs": ["a"]}""", 1)!!.newTerms)
    }

    @Test
    fun userMessageCarriesGlossaryAndParagraphs() {
        val msg = TranslationText.userMessage(listOf("他\"笑\""), listOf(Term("他", "he", "male")))
        assertEquals(
            """{"glossary":[{"source":"他","target":"he","note":"male"}],"paragraphs":["他\"笑\""]}""",
            msg
        )
    }
}
