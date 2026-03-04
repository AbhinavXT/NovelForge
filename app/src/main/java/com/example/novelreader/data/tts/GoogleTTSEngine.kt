package com.example.novelreader.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.novelreader.util.Logger
import java.util.Locale

/**
 * TTS engine backed by Android's built-in TextToSpeech service.
 *
 * This is the "system TTS" path — Google, Samsung, or whatever the user
 * has set as their default TTS engine in Android Settings.
 *
 * Extracted from the original TTSManager so it implements [TTSEngine].
 */
class GoogleTTSEngine(private val context: Context) : TTSEngine, TextToSpeech.OnInitListener {

    companion object {
        const val ENGINE_ID = "google_tts"
        private const val TAG = "GoogleTTSEngine"
    }

    override val displayName: String = "System TTS (Google)"
    override val engineId: String = ENGINE_ID
    override var isReady: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var requestedEngine: String? = null

    // Callbacks stashed during initialize()
    private var initOnReady: (() -> Unit)? = null
    private var initOnError: ((String) -> Unit)? = null

    // Per-utterance callbacks stashed during speak()
    private var utteranceOnStart: (() -> Unit)? = null
    private var utteranceOnDone: (() -> Unit)? = null
    private var utteranceOnError: ((String) -> Unit)? = null

    // Cached voices for the picker
    private var cachedVoices: List<Voice> = emptyList()
    private var currentVoice: Voice? = null

    // ── Lifecycle ────────────────────────────────────────────────

    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        initOnReady = onReady
        initOnError = onError

        requestedEngine = getDefaultTtsEngine()
        Logger.d(TAG, "Initializing with engine: $requestedEngine")

        tts = if (requestedEngine != null) {
            TextToSpeech(context, this, requestedEngine)
        } else {
            TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        Logger.d(TAG, "onInit status=$status, engine=${tts?.defaultEngine}")

        if (status != TextToSpeech.SUCCESS) {
            Logger.e(TAG, "TTS init failed with status: $status")
            isReady = false
            initOnError?.invoke("TTS initialization failed (status=$status)")
            return
        }

        val actualEngine = tts?.defaultEngine
        if (requestedEngine != null && actualEngine != requestedEngine) {
            Logger.w(TAG, "Engine mismatch! Requested=$requestedEngine, Got=$actualEngine")
        }

        if (isNonStandardEngine()) {
            Logger.d(TAG, "Non-standard engine, skipping setLanguage")
        } else {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Logger.e(TAG, "Language not supported")
                isReady = false
                initOnError?.invoke("English language not supported by TTS engine")
                return
            }
        }

        setupUtteranceListener()
        loadAvailableVoices()
        isReady = true
        Logger.d(TAG, "Initialized OK. Voices: ${cachedVoices.size}")
        initOnReady?.invoke()
    }

    // ── Speaking ─────────────────────────────────────────────────

    override fun speak(
        text: String,
        utteranceId: String,
        speed: Float,
        pitch: Float,
        volume: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isReady) {
            onError("GoogleTTSEngine not initialized")
            return
        }

        // Stash callbacks for the UtteranceProgressListener
        utteranceOnStart = onStart
        utteranceOnDone = onDone
        utteranceOnError = onError

        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        Logger.d(TAG, "speak() result=$result for '$utteranceId'")

        if (result == TextToSpeech.ERROR) {
            onError("TextToSpeech.speak() returned ERROR")
        }
    }

    override fun stop() {
        tts?.stop()
        // Clear pending callbacks so they don't fire after stop
        utteranceOnStart = null
        utteranceOnDone = null
        utteranceOnError = null
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // ── Voice management ─────────────────────────────────────────

    override fun getAvailableVoices(): List<VoiceInfo> {
        return cachedVoices.map { voice ->
            VoiceInfo(
                id = voice.name,
                displayName = getVoiceDisplayName(voice),
                isDownloaded = true,
                engineId = ENGINE_ID
            )
        }
    }

    override fun setVoice(voiceId: String) {
        val voice = cachedVoices.find { it.name == voiceId } ?: return
        tts?.voice = voice
        currentVoice = voice
        Logger.d(TAG, "Voice set to: ${voice.name}")
    }

    override fun getCurrentVoiceId(): String? = currentVoice?.name

    /**
     * Return the raw Android [Voice] list for callers that need it
     * (e.g. the existing voice picker in TTSControls).
     */
    fun getAndroidVoices(): List<Voice> = cachedVoices

    fun setAndroidVoice(voice: Voice) {
        tts?.voice = voice
        currentVoice = voice
    }

    fun getCurrentAndroidVoice(): Voice? = currentVoice

    // ── Internals ────────────────────────────────────────────────

    private fun getDefaultTtsEngine(): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error reading default TTS engine", e)
            null
        }
    }

    private fun isNonStandardEngine(): Boolean {
        val engine = tts?.defaultEngine ?: return false
        return engine.contains("sherpa", ignoreCase = true) ||
                engine.contains("piper", ignoreCase = true) ||
                engine.contains("k2fsa", ignoreCase = true) ||
                engine.contains("onnx", ignoreCase = true) ||
                engine.contains("espeak", ignoreCase = true) ||
                engine.contains("rhasspy", ignoreCase = true)
    }

    private fun loadAvailableVoices() {
        tts?.voices?.let { voices ->
            cachedVoices = if (isNonStandardEngine()) {
                voices.sortedBy { it.name }
            } else {
                voices.filter { it.locale.language == "en" }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            }
            Logger.d(TAG, "Loaded ${cachedVoices.size} voices")
        } ?: run {
            Logger.w(TAG, "No voices available")
        }

        // Restore current voice
        currentVoice = tts?.voice
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Logger.d(TAG, "Utterance started: $utteranceId")
                utteranceOnStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                Logger.d(TAG, "Utterance done: $utteranceId")
                utteranceOnDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Logger.e(TAG, "Utterance error: $utteranceId")
                utteranceOnError?.invoke("TTS error on $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Logger.e(TAG, "Utterance error: $utteranceId, code=$errorCode")
                utteranceOnError?.invoke("TTS error $errorCode on $utteranceId")
            }
        })
    }

    /** Human-readable voice label (reused from the original TTSManager). */
    fun getVoiceDisplayName(voice: Voice): String {
        val locale = voice.locale
        val country = locale.displayCountry.ifBlank { locale.country }
        val quality = when {
            voice.name.contains("wavenet", true) -> "WaveNet"
            voice.name.contains("neural", true) -> "Neural"
            voice.name.contains("enhanced", true) -> "Enhanced"
            voice.name.contains("local", true) -> "Local"
            voice.name.contains("piper", true) -> "Piper"
            voice.name.contains("lessac", true) -> "Lessac"
            voice.name.contains("amy", true) -> "Amy"
            else -> if (voice.isNetworkConnectionRequired) "Online" else "Offline"
        }
        val gender = when {
            voice.name.contains("female", true) -> "♀"
            voice.name.contains("male", true) -> "♂"
            voice.name.contains("-f-", true) -> "♀"
            voice.name.contains("-m-", true) -> "♂"
            else -> ""
        }
        return if (country.isNotBlank()) "$country $gender ($quality)"
        else "${voice.name.take(20)} ($quality)"
    }
}