package com.abhinavxt.novelforge.data.translation

import com.abhinavxt.novelforge.data.UserFacingException
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.google.android.gms.tasks.Task
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Translates one chapter's paragraphs, the same number out as in. */
fun interface ChapterTranslator {
    suspend fun translate(paragraphs: List<String>, glossary: List<Term>): TranslationText.Reply
}

/**
 * AI translation, a batch of paragraphs per request. [complete] sends a system prompt and a user
 * message and returns the reply, or null when the reply was cut off. A reply that's cut off or
 * doesn't line up with its paragraphs is retried as two halves, down to single paragraphs.
 */
class AiTranslator(
    private val targetLanguage: String,
    private val complete: suspend (system: String, user: String) -> String?
) : ChapterTranslator {

    override suspend fun translate(paragraphs: List<String>, glossary: List<Term>): TranslationText.Reply {
        val terms = glossary.toMutableList()
        val found = mutableListOf<Term>()
        val out = ArrayList<String>(paragraphs.size)
        for (range in TranslationText.batches(paragraphs, BATCH_CHARS)) {
            out += translateBatch(paragraphs.subList(range.first, range.last + 1), terms, found)
        }
        return TranslationText.Reply(out, found)
    }

    private suspend fun translateBatch(batch: List<String>, terms: MutableList<Term>, found: MutableList<Term>): List<String> {
        val glossary = Glossary.relevant(batch.joinToString("\n"), terms)
        val reply = complete(TranslationText.systemPrompt(targetLanguage), TranslationText.userMessage(batch, glossary))
            ?.let { TranslationText.parseReply(it, batch.size) }
        if (reply == null) {
            if (batch.size == 1) throw UserFacingException("The translator's reply didn't match the chapter. Try again, or try another model.")
            val half = batch.size / 2
            return translateBatch(batch.subList(0, half), terms, found) + translateBatch(batch.subList(half, batch.size), terms, found)
        }
        // Later batches use the names this one settled, so they stay the same all chapter.
        for (term in reply.newTerms) {
            if (terms.none { it.source.equals(term.source, ignoreCase = true) }) {
                terms += term
                found += term
            }
        }
        return reply.paragraphs.mapIndexed { i, t -> TranslationText.clean(t, batch[i]) }
    }

    companion object {
        /** Characters per request: about a third of a long chapter, well inside any model's output. */
        const val BATCH_CHARS = 4000
    }
}

/** Claude through Anthropic's SDK. Close it when the chapter is done. */
class ClaudeCompletion(apiKey: String, private val model: String) : AutoCloseable {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    suspend fun complete(system: String, user: String): String? = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(user)
            .apply {
                // Fiction trips safety classifiers now and then; let the API retry a declined request on a model that can take it.
                if (model in SERVER_FALLBACK_MODELS) {
                    addBeta("server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        val message = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw UserFacingException("Claude didn't accept the API key. Check it in Settings → Translation.", e)
        } catch (e: PermissionDeniedException) {
            throw UserFacingException("This API key can't use $model.", e)
        } catch (e: RateLimitException) {
            throw UserFacingException("Claude is rate-limiting this key. Try again in a minute.", e)
        } catch (e: AnthropicServiceException) {
            throw UserFacingException("Claude returned an error (${e.statusCode()}): ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw UserFacingException("Couldn't reach Claude. Check your connection.", e)
        }
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw UserFacingException("Claude declined to translate this chapter.")
            BetaStopReason.MAX_TOKENS -> null
            else -> message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        }
    }

    override fun close() = client.close()

    companion object {
        /** Models that take the server-side `fallbacks: "default"` parameter. */
        private val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}

/** Any OpenAI-compatible chat endpoint: OpenAI, Gemini, DeepSeek, OpenRouter, or the reader's own. */
class OpenAiCompatibleCompletion(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = sharedHttp
) {
    suspend fun complete(system: String, user: String): String? = withContext(Dispatchers.IO) {
        val messages = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
        }
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JsonParser.parseString(text).asJsonObject.getAsJsonObject("error").get("message").asString
                    }.getOrNull() ?: text.take(200)
                    throw UserFacingException(
                        when (response.code) {
                            401, 403 -> "The translator didn't accept the API key. Check it in Settings → Translation."
                            429 -> "The translator is rate-limiting this key. Try again in a minute."
                            else -> "The translator returned an error (${response.code}): $detail"
                        }
                    )
                }
                text
            }
        } catch (e: IOException) {
            throw UserFacingException("Couldn't reach the translator at $baseUrl. Check the address and your connection.", e)
        }
        val choice = runCatching { JsonParser.parseString(text).asJsonObject.getAsJsonArray("choices")[0].asJsonObject }
            .getOrNull() ?: throw UserFacingException("The translator sent back something that isn't a chat reply.")
        if (choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString == "length") return@withContext null
        choice.getAsJsonObject("message")?.get("content")?.takeIf { !it.isJsonNull }?.asString
    }

    companion object {
        private val sharedHttp by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
        }
    }
}

/**
 * Google's offline translator. It can't polish English that's already English, so when the chapter
 * is already in [target] it only applies the glossary. Glossary names go in before translating
 * (a Latin-script name passes through untouched) and once more after.
 */
class OnDeviceTranslator(private val source: String, private val target: String) : ChapterTranslator {

    override suspend fun translate(paragraphs: List<String>, glossary: List<Term>): TranslationText.Reply {
        if (source.substringBefore('-') == target.substringBefore('-')) {
            return TranslationText.Reply(paragraphs.map { Glossary.apply(it, glossary) }, emptyList())
        }
        val from = TranslateLanguage.fromLanguageTag(source)
            ?: throw UserFacingException("The on-device translator can't read ${languageName(source)}.")
        val to = TranslateLanguage.fromLanguageTag(target)
            ?: throw UserFacingException("The on-device translator can't write ${languageName(target)}.")
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            try {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw UserFacingException(
                    "Couldn't get the on-device language model. The first time needs an internet connection, " +
                        "and some phones need Google Play services.", e
                )
            }
            val out = paragraphs.map { p ->
                val translated = client.translate(Glossary.apply(p, glossary)).await()
                TranslationText.clean(Glossary.apply(translated, glossary), p)
            }
            return TranslationText.Reply(out, emptyList())
        } finally {
            client.close()
        }
    }

    private fun languageName(tag: String) =
        java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { tag }
}

/**
 * The language of [text] by ML Kit's offline identifier, or null when it isn't sure. The script
 * check in [TranslationText.detectLanguage] only knows Chinese, Japanese and Korean, so without
 * this a French or Spanish book read as English and on-device "translation" left it untouched.
 */
suspend fun identifyLanguage(text: String): String? {
    val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    return try {
        client.identifyLanguage(text.take(2000)).await().takeIf { it != "und" }
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
