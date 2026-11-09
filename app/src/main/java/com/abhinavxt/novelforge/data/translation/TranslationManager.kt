package com.abhinavxt.novelforge.data.translation

import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.database.ChapterOriginalEntity
import com.abhinavxt.novelforge.data.database.GlossaryTermEntity
import com.abhinavxt.novelforge.data.database.TranslationDao
import com.abhinavxt.novelforge.util.ParagraphSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Translated chapters replace their stored text, so the reader, search, TTS, highlights and export
 * all see the translation with no changes of their own. The original goes to chapter_originals,
 * along with a hash of the translation: when something else rewrites the chapter (a re-download,
 * an archive restore), the hash stops matching and the chapter simply counts as untranslated again.
 *
 * [translatorFor] picks the engine; tests pass their own.
 */
class TranslationManager(
    private val dao: TranslationDao,
    private val settings: TranslationSettings?,
    private val translatorFor: (suspend (novelId: String, sample: String) -> Pair<ChapterTranslator, String>)? = null
) {
    /** One lock per chapter, so the reader and the background job never pay for the same chapter twice. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    fun observeTranslateOn(novelId: String): Flow<Boolean> = dao.observeTranslateOn(novelId).map { it == true }

    suspend fun isTranslateOn(novelId: String): Boolean = dao.isTranslateOn(novelId) == true

    suspend fun setTranslateOn(novelId: String, on: Boolean) = dao.setTranslate(novelId, on)

    fun observeGlossary(novelId: String): Flow<List<GlossaryTermEntity>> = dao.observeGlossary(novelId)

    suspend fun saveTerm(term: GlossaryTermEntity) {
        if (term.source.isNotBlank() && term.target.isNotBlank()) {
            dao.saveTerm(term.copy(source = term.source.trim(), target = term.target.trim(), note = term.note.trim()))
        }
    }

    suspend fun deleteTerm(id: Long) = dao.deleteTerm(id)

    suspend fun isTranslated(chapterId: String, content: String): Boolean =
        dao.getOriginal(chapterId)?.translatedHash == content.hashCode()

    /** What to show for [content]: itself, or, in a book set to translate, its translation (made and stored on first use). */
    suspend fun forReading(novelId: String, chapterId: String, content: String): String =
        if (!isTranslateOn(novelId) || isTranslated(chapterId, content)) content
        else translateChapter(novelId, chapterId, content)

    /** Translates and stores one chapter, returning the translation. [content] is its current text. */
    suspend fun translateChapter(novelId: String, chapterId: String, content: String): String =
        locks.getOrPut(chapterId) { Mutex() }.withLock {
        // Someone may have translated it while we waited.
        val stored = dao.getContent(chapterId)
        if (stored != null && isTranslated(chapterId, stored)) return@withLock stored
        if (isTranslated(chapterId, content)) return@withLock content

        val paragraphs = ParagraphSplitter.split(content)
        if (paragraphs.isEmpty()) return@withLock content
        val glossary = dao.getGlossary(novelId).map { Term(it.source, it.target, it.note) }
        val (translator, engine) = (translatorFor ?: ::defaultTranslator)(novelId, content)
        val reply = translator.translate(paragraphs, glossary)
        val translated = reply.paragraphs.joinToString("\n\n")

        dao.addTerms(reply.newTerms.map { GlossaryTermEntity(novelId = novelId, source = it.source, target = it.target, note = it.note) })
        dao.setContent(chapterId, translated)
        dao.saveOriginal(ChapterOriginalEntity(chapterId, novelId, content, translated.hashCode(), engine))
        translated
    }

    /** Stored chapters of the book that aren't translated yet, in reading order. */
    suspend fun untranslatedChapters(novelId: String): List<String> {
        val originals = dao.getOriginals(novelId).associate { it.chapterId to it.translatedHash }
        return dao.getStoredChapterIds(novelId).filter { originals[it.id] != it.content.hashCode() }.map { it.id }
    }

    suspend fun translateStoredChapter(novelId: String, chapterId: String) {
        val content = dao.getContent(chapterId) ?: return
        translateChapter(novelId, chapterId, content)
    }

    /** Puts every translated chapter of the book back the way it was. Returns how many. */
    suspend fun restoreBook(novelId: String): Int {
        var restored = 0
        for (original in dao.getOriginals(novelId)) {
            if (dao.getContent(original.chapterId)?.hashCode() == original.translatedHash) {
                dao.setContent(original.chapterId, original.original)
                restored++
            }
            dao.deleteOriginal(original.chapterId)
        }
        return restored
    }

    /** Where a "translate the downloaded chapters" run is. Null when none has run. */
    data class BulkProgress(
        val novelId: String,
        val done: Int,
        val total: Int,
        val running: Boolean = true,
        val error: String? = null
    )

    private val _bulk = MutableStateFlow<BulkProgress?>(null)
    val bulk: StateFlow<BulkProgress?> = _bulk.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bulkJob: Job? = null

    /**
     * Translates the book's stored chapters that aren't yet, one by one, for as long as the app runs
     * (like bulk downloads). Stops at the first failure: a bad key or a lost connection fails them all.
     */
    fun translateStoredChapters(novelId: String) {
        if (bulkJob?.isActive == true) return
        bulkJob = scope.launch {
            val ids = untranslatedChapters(novelId)
            var done = 0
            _bulk.value = BulkProgress(novelId, 0, ids.size)
            try {
                for (id in ids) {
                    translateStoredChapter(novelId, id)
                    done++
                    _bulk.value = BulkProgress(novelId, done, ids.size)
                }
                _bulk.value = BulkProgress(novelId, done, ids.size, running = false)
            } catch (e: CancellationException) {
                _bulk.value = BulkProgress(novelId, done, ids.size, running = false)
                throw e
            } catch (e: Exception) {
                val message = (e as? UserFacingException)?.message ?: "Translation failed: ${e.message}"
                _bulk.value = BulkProgress(novelId, done, ids.size, running = false, error = message)
            }
        }
    }

    fun stopBulk() {
        bulkJob?.cancel()
    }

    private suspend fun defaultTranslator(novelId: String, sample: String): Pair<ChapterTranslator, String> {
        val config = settings?.config?.value ?: TranslationConfig()
        return when (config.engine) {
            TranslationEngine.ON_DEVICE -> {
                val source = dao.getLanguage(novelId) ?: TranslationText.detectLanguage(sample) ?: config.targetLanguage
                OnDeviceTranslator(source, config.targetLanguage) to ENGINE_ON_DEVICE
            }
            TranslationEngine.AI -> {
                if (config.model.isBlank()) throw UserFacingException("Choose a model in Settings → Translation.")
                when (config.provider) {
                    AiProvider.CLAUDE -> {
                        if (config.apiKey.isBlank()) throw UserFacingException("Add your Claude API key in Settings → Translation.")
                        // One client per chapter, closed once the chapter is done.
                        val translator = ChapterTranslator { paragraphs, glossary ->
                            ClaudeCompletion(config.apiKey, config.model).use { claude ->
                                AiTranslator(config.targetLanguageName, claude::complete).translate(paragraphs, glossary)
                            }
                        }
                        translator to ENGINE_AI
                    }
                    else -> {
                        if (config.baseUrl.isBlank()) throw UserFacingException("Add the translator's address in Settings → Translation.")
                        if (config.apiKey.isBlank() && config.provider != AiProvider.CUSTOM) {
                            throw UserFacingException("Add your ${config.provider.label} API key in Settings → Translation.")
                        }
                        val api = OpenAiCompatibleCompletion(config.baseUrl, config.apiKey, config.model)
                        AiTranslator(config.targetLanguageName, api::complete) to ENGINE_AI
                    }
                }
            }
        }
    }

    companion object {
        const val ENGINE_AI = "ai"
        const val ENGINE_ON_DEVICE = "on-device"
    }
}
