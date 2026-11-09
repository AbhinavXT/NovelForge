package com.abhinavxt.novelforge.data.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A glossary entry: write [source] as [target]; [note] says e.g. "female, she/her". */
data class Term(val source: String, val target: String, val note: String = "")

/** Swaps glossary terms into text, in one pass, longest term first. */
object Glossary {

    private val cjk = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL
    )

    private fun hasCjk(s: String) = s.any { Character.UnicodeScript.of(it.code) in cjk }

    fun apply(text: String, terms: List<Term>): String {
        val usable = terms.filter { it.source.isNotBlank() }.sortedByDescending { it.source.length }
        if (usable.isEmpty()) return text
        val lookup = LinkedHashMap<String, String>()
        usable.forEach { lookup.putIfAbsent(it.source.lowercase(), it.target) }
        val pattern = usable.joinToString("|") { term ->
            val escaped = Regex.escape(term.source)
            // CJK has no spaces between words, so a name sits flush against the next character.
            if (hasCjk(term.source)) escaped else "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])"
        }
        return Regex(pattern, RegexOption.IGNORE_CASE).replace(text) { lookup[it.value.lowercase()] ?: it.value }
    }

    /** The terms worth sending with [text]: the ones it mentions. */
    fun relevant(text: String, terms: List<Term>): List<Term> =
        terms.filter { it.source.isNotBlank() && text.contains(it.source, ignoreCase = true) }
}

object TranslationText {

    /**
     * The language a chapter is written in, judged by its script: "zh", "ja", "ko", or null for
     * anything else (usually English MTL, which the on-device engine can't improve).
     */
    fun detectLanguage(text: String): String? {
        var han = 0; var kana = 0; var hangul = 0; var latin = 0
        for (c in text.take(4000)) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.LATIN -> latin++
                else -> Unit
            }
        }
        return when {
            hangul > latin && hangul >= han -> "ko"
            kana > 0 && kana * 10 >= han && kana + han > latin -> "ja"
            han > latin -> "zh"
            else -> null
        }
    }

    /** Splits paragraphs into consecutive batches of at most [maxChars]; a longer paragraph goes alone. */
    fun batches(paragraphs: List<String>, maxChars: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        var size = 0
        paragraphs.forEachIndexed { i, p ->
            if (i > start && size + p.length > maxChars) {
                out += start until i
                start = i
                size = 0
            }
            size += p.length
        }
        if (start < paragraphs.size) out += start until paragraphs.size
        return out
    }

    /**
     * One translated paragraph made safe to store: it must stay one paragraph, so the chapter keeps
     * its paragraph count and bookmarks, highlights and reading position stay on the right paragraph.
     */
    fun clean(translated: String, original: String): String =
        translated.replace(Regex("\\s*\\n+\\s*"), " ").trim().ifEmpty { original }

    fun systemPrompt(targetLanguage: String): String = """
        You translate web novel chapters into $targetLanguage. The input may be raw text in another language or a rough machine translation into $targetLanguage. Either way, write fluent, natural $targetLanguage prose that keeps the meaning, tone and every detail. Don't summarize, skip, add or merge anything.

        Use the glossary exactly: wherever a glossary source term appears, write its target form. Follow the notes for gender and pronouns.

        The input is JSON: {"glossary": [{"source", "target", "note"}], "paragraphs": [...]}.
        Reply with JSON only, without code fences: {"paragraphs": [...], "new_terms": [{"source", "target", "note"}]}.
        "paragraphs" must have exactly as many items as the input, item i translating input item i.
        "new_terms" lists recurring names of characters, places, sects, titles and techniques that you translated and that aren't in the glossary yet, as they appear in the input (source) and as you wrote them (target). For people, the note gives their gender and pronouns, for example "female, she/her". Leave it empty when there are none.
    """.trimIndent()

    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

    fun userMessage(paragraphs: List<String>, glossary: List<Term>): String {
        val obj = JsonObject()
        obj.add("glossary", gson.toJsonTree(glossary.map { mapOf("source" to it.source, "target" to it.target, "note" to it.note) }))
        obj.add("paragraphs", gson.toJsonTree(paragraphs))
        return gson.toJson(obj)
    }

    data class Reply(val paragraphs: List<String>, val newTerms: List<Term>)

    /** The model's reply, or null when it isn't the JSON asked for or has the wrong paragraph count. */
    fun parseReply(reply: String, expected: Int): Reply? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JsonParser.parseString(reply.substring(start, end + 1)).asJsonObject }.getOrNull() ?: return null
        val paragraphs = runCatching {
            obj.getAsJsonArray("paragraphs").map { if (it.isJsonNull) "" else it.asString }
        }.getOrNull() ?: return null
        if (paragraphs.size != expected) return null
        val terms = runCatching {
            obj.getAsJsonArray("new_terms")?.mapNotNull { e ->
                val t = e.asJsonObject
                val source = t.get("source")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                val target = t.get("target")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                val note = t.get("note")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (source.isEmpty() || target.isEmpty()) null else Term(source, target, note)
            }
        }.getOrNull().orEmpty()
        return Reply(paragraphs, terms)
    }
}
