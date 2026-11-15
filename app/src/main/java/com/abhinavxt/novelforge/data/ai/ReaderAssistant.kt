package com.abhinavxt.novelforge.data.ai

import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.database.ChapterDao
import com.abhinavxt.novelforge.data.database.ChapterText
import com.abhinavxt.novelforge.data.translation.AiCompletions
import com.abhinavxt.novelforge.data.translation.TranslationConfig
import com.abhinavxt.novelforge.data.translation.TranslationSettings
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ParagraphSplitter
import kotlinx.coroutines.CancellationException

/** What an assistant call came to. */
sealed interface AssistantResult {
    data class Success(val text: String) : AssistantResult
    /** No AI engine is set up; nothing was sent. */
    data object NotConfigured : AssistantResult
    /** Nothing the reader has read is stored on the device to work from; nothing was sent. */
    data object NoText : AssistantResult
    data class Failed(val message: String) : AssistantResult
}

/**
 * Two optional helpers that reuse the AI engine set up for translation: a summary of the story so
 * far, and answers about a character. Only text up to the reader's position is ever sent, and only
 * when the reader asks for it.
 */
class ReaderAssistant(
    private val settings: TranslationSettings,
    private val chapters: ChapterDao,
    private val repository: NovelRepository
) {
    val isAvailable: Boolean get() = isAvailable(settings.config.value)

    suspend fun storySoFar(novelId: String, upToChapterNumber: Int, upToParagraphIndex: Int? = null): AssistantResult {
        val config = settings.config.value
        if (!isAvailable(config)) return AssistantResult.NotConfigured
        // Only the recent chapters can fit the budget, so don't load a whole long book to pick from.
        val texts = chapters.getStoredTexts(novelId, (upToChapterNumber - STORY_CHAPTERS).coerceAtLeast(1), upToChapterNumber)
        val passages = AssistantContext.storyPassages(texts, upToChapterNumber, upToParagraphIndex)
        if (passages.isEmpty()) return AssistantResult.NoText
        return ask(config, AssistantContext.STORY_SYSTEM, AssistantContext.render(passages) + "\n\nWrite the summary.")
    }

    suspend fun askAboutCharacter(
        novelId: String,
        name: String,
        question: String,
        upToChapterNumber: Int,
        upToParagraphIndex: Int? = null
    ): AssistantResult {
        val config = settings.config.value
        if (!isAvailable(config)) return AssistantResult.NotConfigured
        // Few chapters, not hundreds: the first appearance and the most recent ones fill the budget anyway.
        val numbers = repository.getCodexMentionChapterNumbers(novelId, name, upToChapterNumber).distinct().sorted()
        val wanted = (numbers.take(1) + numbers.takeLast(MENTION_CHAPTERS)).distinct()
        val texts = wanted.flatMap { chapters.getStoredTexts(novelId, it, it) }
        val passages = AssistantContext.characterPassages(texts, name, upToChapterNumber, upToParagraphIndex)
        if (passages.isEmpty()) return AssistantResult.NoText
        val user = "Character: $name\n\nPassages:\n" + AssistantContext.render(passages) + "\n\nQuestion: ${question.trim()}"
        return ask(config, AssistantContext.CHARACTER_SYSTEM, user)
    }

    private suspend fun ask(config: TranslationConfig, system: String, user: String): AssistantResult = try {
        val reply = AiCompletions.use(config) { complete -> complete(system, user) }?.trim()
        if (reply.isNullOrEmpty()) AssistantResult.Failed("The reply came back empty or cut off. Try again.")
        else AssistantResult.Success(reply)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserFacingException) {
        AssistantResult.Failed(e.message ?: "The request failed.")
    } catch (e: Exception) {
        // Never log the texts or the key, only what kind of failure it was.
        Logger.e("ReaderAssistant", "request failed: ${e.javaClass.simpleName}")
        AssistantResult.Failed("The request failed. Check your connection and try again.")
    }

    companion object {
        private const val MENTION_CHAPTERS = 40
        private const val STORY_CHAPTERS = 30

        /** An AI engine with a key is set up. Offline translation doesn't count: it can't write. */
        fun isAvailable(config: TranslationConfig): Boolean = AiCompletions.isConfigured(config)
    }
}

/** One stretch of text from a chapter. */
data class Passage(val chapterNumber: Int, val text: String)

/** Choosing and framing what's sent: pure, so the reading-position and budget rules are testable. */
object AssistantContext {
    /** Characters of book text per request: roughly 6,000 tokens, within any model's window. */
    const val BUDGET = 24_000

    /** A single paragraph is cut to this, so one wall of text can't use the whole budget. */
    private const val MAX_PARAGRAPH = 1_500

    const val STORY_SYSTEM = "You summarise what has happened so far in a novel, for a reader who is " +
        "partway through it. Use only the text supplied. Do not use any outside knowledge of the book, " +
        "and do not speculate about what happens next. Write 5 to 8 short bullet points, then one final " +
        "line beginning \"Where you left off:\". Answer in the language of the text."

    const val CHARACTER_SYSTEM = "You answer a reader's question about a character in a novel, using only " +
        "the passages supplied, which are everything the reader has read that mentions them. Do not use " +
        "any outside knowledge of the book and do not speculate about what happens next. If the passages " +
        "do not contain the answer, say so in one sentence rather than guessing. Keep the answer short. " +
        "Answer in the language of the question."

    /**
     * The chapters up to the reader's position, newest first until [budget] is used, then back in
     * reading order. In the chapter being read, nothing after [upToParagraph] is included. The
     * chapter that doesn't fully fit contributes its last paragraphs; nothing earlier is added after it.
     */
    fun storyPassages(
        texts: List<ChapterText>,
        upToChapter: Int,
        upToParagraph: Int?,
        budget: Int = BUDGET
    ): List<Passage> {
        val chosen = ArrayDeque<Passage>()
        var left = budget
        for (chapter in texts.filter { it.number <= upToChapter }.sortedByDescending { it.number }) {
            var paragraphs = ParagraphSplitter.split(chapter.content)
            if (chapter.number == upToChapter && upToParagraph != null) {
                paragraphs = paragraphs.take((upToParagraph + 1).coerceAtLeast(0))
            }
            if (paragraphs.isEmpty()) continue
            val whole = paragraphs.joinToString("\n\n")
            if (whole.length <= left) {
                chosen.addFirst(Passage(chapter.number, whole))
                left -= whole.length
                continue
            }
            val tail = ArrayDeque<String>()
            for (p in paragraphs.asReversed()) {
                val clipped = p.take(MAX_PARAGRAPH)
                if (clipped.length > left) break
                tail.addFirst(clipped)
                left -= clipped.length
            }
            if (tail.isNotEmpty()) chosen.addFirst(Passage(chapter.number, tail.joinToString("\n\n")))
            break
        }
        return chosen.toList()
    }

    /**
     * Paragraphs that mention [name] (whole word, any case), up to the reader's position. The first
     * appearance is always kept, then the most recent mentions fill what's left of [budget]; the
     * result is in reading order.
     */
    fun characterPassages(
        texts: List<ChapterText>,
        name: String,
        upToChapter: Int,
        upToParagraph: Int?,
        budget: Int = BUDGET
    ): List<Passage> {
        val word = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val hits = texts.filter { it.number <= upToChapter }.sortedBy { it.number }.flatMap { chapter ->
            var paragraphs = ParagraphSplitter.split(chapter.content)
            if (chapter.number == upToChapter && upToParagraph != null) {
                paragraphs = paragraphs.take((upToParagraph + 1).coerceAtLeast(0))
            }
            paragraphs.filter { word.containsMatchIn(it) }.map { Passage(chapter.number, it.take(MAX_PARAGRAPH)) }
        }
        if (hits.isEmpty()) return emptyList()
        val kept = mutableSetOf(0)
        var left = budget - hits[0].text.length
        for (i in hits.indices.reversed()) {
            if (i == 0) continue
            if (hits[i].text.length > left) break
            kept += i
            left -= hits[i].text.length
        }
        return kept.sorted().map { hits[it] }
    }

    /** The passages as one block, each chapter under its number. */
    fun render(passages: List<Passage>): String = passages
        .groupBy { it.chapterNumber }
        .entries
        .joinToString("\n\n") { (number, group) -> "[Chapter $number]\n" + group.joinToString("\n\n") { it.text } }
}
