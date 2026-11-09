package com.abhinavxt.novelforge.data.translation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TranslationEngine(val label: String) {
    AI("AI, with your key"),
    ON_DEVICE("On-device")
}

/**
 * Where AI translation goes. Claude uses Anthropic's own SDK; the rest speak the
 * OpenAI-compatible chat API at [baseUrl] (CUSTOM: the reader's own URL).
 */
enum class AiProvider(val label: String, val baseUrl: String?, val defaultModel: String) {
    CLAUDE("Claude", null, "claude-opus-5-5"),
    OPENAI("OpenAI", "https://api.openai.com/v1", ""),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", ""),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", ""),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", ""),
    CUSTOM("Other (OpenAI-compatible)", null, "")
}

data class TranslationConfig(
    val engine: TranslationEngine = TranslationEngine.AI,
    val provider: AiProvider = AiProvider.CLAUDE,
    /** The model for [provider]; each provider remembers its own. */
    val model: String = provider.defaultModel,
    /** The key for [provider]; each provider remembers its own. */
    val apiKey: String = "",
    val customUrl: String = "",
    /** BCP 47 tag of the language to translate into. */
    val targetLanguage: String = "en"
) {
    val targetLanguageName: String
        get() = Locale.forLanguageTag(targetLanguage).getDisplayLanguage(Locale.ENGLISH).ifEmpty { targetLanguage }

    val baseUrl: String
        get() = (provider.baseUrl ?: customUrl).trim().trimEnd('/')
}

/**
 * Translation settings. Kept in their own preferences file, which backups skip,
 * because it holds API keys.
 */
class TranslationSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<TranslationConfig> = _config.asStateFlow()

    private fun load(): TranslationConfig {
        val provider = runCatching { AiProvider.valueOf(prefs.getString(KEY_PROVIDER, null)!!) }.getOrDefault(AiProvider.CLAUDE)
        return TranslationConfig(
            engine = runCatching { TranslationEngine.valueOf(prefs.getString(KEY_ENGINE, null)!!) }.getOrDefault(TranslationEngine.AI),
            provider = provider,
            model = prefs.getString(KEY_MODEL + provider.name, null) ?: provider.defaultModel,
            apiKey = prefs.getString(KEY_API_KEY + provider.name, null).orEmpty(),
            customUrl = prefs.getString(KEY_CUSTOM_URL, null).orEmpty(),
            targetLanguage = prefs.getString(KEY_TARGET, null) ?: "en"
        )
    }

    fun setEngine(engine: TranslationEngine) = save { putString(KEY_ENGINE, engine.name) }

    /** Switches provider and brings back the model and key used with it last time. */
    fun setProvider(provider: AiProvider) = save { putString(KEY_PROVIDER, provider.name) }

    fun setModel(model: String) = save { putString(KEY_MODEL + _config.value.provider.name, model.trim()) }

    fun setApiKey(key: String) = save { putString(KEY_API_KEY + _config.value.provider.name, key.trim()) }

    fun setCustomUrl(url: String) = save { putString(KEY_CUSTOM_URL, url.trim()) }

    fun setTargetLanguage(tag: String) = save { putString(KEY_TARGET, tag) }

    private fun save(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _config.value = load()
    }

    companion object {
        /** Excluded in backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS = "translation"
        private const val KEY_ENGINE = "engine"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model_"
        private const val KEY_API_KEY = "api_key_"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_TARGET = "target_language"
    }
}
