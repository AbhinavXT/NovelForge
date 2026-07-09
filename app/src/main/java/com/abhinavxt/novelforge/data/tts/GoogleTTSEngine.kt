package com.abhinavxt.novelforge.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
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

    // ── Audio export support ──────────────────────────────────────

    /**
     * Synthesize text to a WAV file using Android's built-in TTS.
     * Returns true on success. The file will be a valid WAV.
     */
    suspend fun synthesizeToFile(
        text: String,
        outputFile: java.io.File,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (!isReady || tts == null) {
            cont.resume(false) {}
            return@suspendCancellableCoroutine
        }

        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)

        val utteranceId = "export_${System.currentTimeMillis()}"

        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) {
                    // Restore the playback listener before resuming
                    setupUtteranceListener()
                    cont.resume(true) {}
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) {
                    setupUtteranceListener()
                    cont.resume(false) {}
                }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && cont.isActive) {
                    setupUtteranceListener()
                    cont.resume(false) {}
                }
            }
        }

        tts?.setOnUtteranceProgressListener(listener)

        val result = tts?.synthesizeToFile(text, null, outputFile, utteranceId)
        if (result == TextToSpeech.ERROR) {
            setupUtteranceListener()
            if (cont.isActive) cont.resume(false) {}
        }

        cont.invokeOnCancellation {
            tts?.stop()
            setupUtteranceListener()
        }
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
                    .sortedWith(
                        compareBy<Voice> { it.locale.displayCountry }
                            .thenBy { it.isNetworkConnectionRequired }  // local first
                            .thenBy { extractVariantCode(it.name) }
                    )
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

    /** Human-readable voice label with variant name for differentiation. */
    fun getVoiceDisplayName(voice: Voice): String {
        val locale = voice.locale
        val lang = locale.displayLanguage.ifBlank { locale.language }
        val country = locale.displayCountry.ifBlank { locale.country }

        // Extract variant code from voice name (e.g. "en-us-x-sfg-local" → "sfg")
        val variantCode = extractVariantCode(voice.name)

        // Try to get a human-readable voice name from known mappings
        val voiceName = KNOWN_VOICE_NAMES[variantCode]

        // Detect quality tier from the voice name
        val quality = when {
            voice.name.contains("wavenet", true) -> "WaveNet"
            voice.name.contains("neural", true) -> "Neural"
            voice.name.contains("enhanced", true) -> "Enhanced"
            voice.name.contains("studio", true) -> "Studio"
            voice.name.contains("local", true) -> "Local"
            voice.name.contains("network", true) -> "Online"
            !voice.isNetworkConnectionRequired -> "Local"
            else -> "Online"
        }

        // Detect gender
        val gender = when {
            voice.name.contains("female", true) || voice.name.contains("-f-", true) -> "♀"
            voice.name.contains("male", true) || voice.name.contains("-m-", true) -> "♂"
            variantCode in FEMALE_VARIANTS -> "♀"
            variantCode in MALE_VARIANTS -> "♂"
            else -> ""
        }

        // Build the display name
        val region = country.ifBlank { lang }
        return if (voiceName != null) {
            "$region · $voiceName $gender ($quality)"
        } else if (variantCode.isNotBlank()) {
            "$region · Voice ${variantCode.uppercase()} $gender ($quality)"
        } else {
            "$region $gender ($quality)"
        }
    }

    /**
     * Extract the voice variant code from the Android TTS voice name.
     * Handles formats like:
     *   "en-us-x-sfg-local"         → "sfg"
     *   "en-GB-x-gba-network"       → "gba"
     *   "en-us-x-tpd#male_1-local"  → "tpd"
     */
    private fun extractVariantCode(voiceName: String): String {
        val xIndex = voiceName.indexOf("-x-")
        if (xIndex < 0) return ""

        val afterX = voiceName.substring(xIndex + 3)
        val endIdx = afterX.indexOfFirst { it == '-' || it == '#' }
        return if (endIdx > 0) afterX.substring(0, endIdx) else afterX
    }

    companion object {
        const val ENGINE_ID = "google_tts"
        private const val TAG = "GoogleTTSEngine"

        // Known Google TTS English voice variant mappings
        private val KNOWN_VOICE_NAMES = mapOf(
            // US English
            "sfg" to "Aria",
            "tpc" to "River",
            "tpd" to "Asher",
            "tpf" to "Luna",
            "iob" to "Ivy",
            "iol" to "Drew",
            "iom" to "Blake",
            "ion" to "Nova",
            "iog" to "Pearl",
            "tpg" to "Reed",
            "efl" to "Sage",
            "efg" to "Dawn",
            // UK English
            "gba" to "Rosie",
            "gbb" to "James",
            "gbc" to "Lily",
            "gbd" to "Oliver",
            "rjs" to "Robin",
            // Australian English
            "auc" to "Freya",
            "aud" to "Max",
            "aub" to "Ruby",
            // Indian English
            "ina" to "Priya",
            "inb" to "Raj",
            "inc" to "Ananya",
            "ind" to "Arjun",
        )

        private val FEMALE_VARIANTS = setOf(
            "sfg", "tpf", "iob", "ion", "iog", "efg",
            "gba", "gbc",
            "auc", "aub",
            "ina", "inc"
        )
        private val MALE_VARIANTS = setOf(
            "tpc", "tpd", "iol", "iom", "tpg", "efl",
            "gbb", "gbd", "rjs",
            "aud",
            "inb", "ind"
        )
    }
}