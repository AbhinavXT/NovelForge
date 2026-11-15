package com.abhinavxt.novelforge.data.translation

import com.abhinavxt.novelforge.data.UserFacingException

/**
 * The one place the user's AI engine (Settings → Translation) becomes a completion client, so
 * translation and the reader's helpers always reach the same provider with the same key.
 */
object AiCompletions {

    /** An engine, model and key (or address, for the reader's own server) are all set. */
    fun isConfigured(config: TranslationConfig): Boolean = problem(config) == null

    /** Throws the message the reader should see if [config] can't make AI calls yet. */
    fun check(config: TranslationConfig) {
        problem(config)?.let { throw UserFacingException(it) }
    }

    private fun problem(config: TranslationConfig): String? = when {
        config.engine != TranslationEngine.AI -> "Choose an AI engine in Settings → Translation."
        config.model.isBlank() -> "Choose a model in Settings → Translation."
        config.provider == AiProvider.CLAUDE ->
            "Add your Claude API key in Settings → Translation.".takeIf { config.apiKey.isBlank() }
        config.baseUrl.isBlank() -> "Add the translator's address in Settings → Translation."
        config.apiKey.isBlank() && config.provider != AiProvider.CUSTOM ->
            "Add your ${config.provider.label} API key in Settings → Translation."
        else -> null
    }

    /** Runs [block] with a completion call for [config]'s provider; a Claude client is closed after. */
    suspend fun <T> use(config: TranslationConfig, block: suspend (suspend (String, String) -> String?) -> T): T {
        check(config)
        return when (config.provider) {
            AiProvider.CLAUDE -> ClaudeCompletion(config.apiKey, config.model).use { claude -> block(claude::complete) }
            else -> block(OpenAiCompatibleCompletion(config.baseUrl, config.apiKey, config.model)::complete)
        }
    }
}
