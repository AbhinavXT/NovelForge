package com.example.novelreader.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TTSState {
    IDLE,
    PLAYING,
    PAUSED,
    LOADING,
    ERROR
}

data class TTSSettings(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceName: String? = null
)

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var requestedEngine: String? = null

    private val prefs = context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TTSState.IDLE)
    val state: StateFlow<TTSState> = _state.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private val _currentVoice = MutableStateFlow<Voice?>(null)
    val currentVoice: StateFlow<Voice?> = _currentVoice.asStateFlow()

    private val _settings = MutableStateFlow(TTSSettings())
    val settings: StateFlow<TTSSettings> = _settings.asStateFlow()

    private val _shouldAutoContinue = MutableStateFlow(false)
    val shouldAutoContinue: StateFlow<Boolean> = _shouldAutoContinue.asStateFlow()

    private var sentences: List<String> = emptyList()
    private var currentIndex = 0
    private var onChapterComplete: (() -> Unit)? = null

    init {
        // Get the default TTS engine from system settings
        requestedEngine = getDefaultTtsEngine()
        Log.d("TTSManager", "Default TTS engine from settings: $requestedEngine")

        // Use the constructor that specifies the engine
        tts = if (requestedEngine != null) {
            TextToSpeech(context, this, requestedEngine)
        } else {
            TextToSpeech(context, this)
        }

        loadSavedSettings()
    }

    /**
     * Get the default TTS engine package name from system settings
     */
    private fun getDefaultTtsEngine(): String? {
        return try {
            val resolver = context.contentResolver
            android.provider.Settings.Secure.getString(
                resolver,
                "tts_default_synth"
            )
        } catch (e: Exception) {
            Log.e("TTSManager", "Error getting default TTS engine", e)
            null
        }
    }

    /**
     * Check if the current engine is a non-standard TTS (like Sherpa/Piper)
     * These engines often don't support setLanguage properly
     */
    private fun isNonStandardEngine(): Boolean {
        val engine = tts?.defaultEngine ?: return false
        return engine.contains("sherpa", ignoreCase = true) ||
                engine.contains("piper", ignoreCase = true) ||
                engine.contains("k2fsa", ignoreCase = true) ||
                engine.contains("onnx", ignoreCase = true) ||
                engine.contains("espeak", ignoreCase = true) ||
                engine.contains("rhasspy", ignoreCase = true)
    }

    private fun loadSavedSettings() {
        val speed = prefs.getFloat("tts_speed", 1.0f)
        val pitch = prefs.getFloat("tts_pitch", 1.0f)
        val voiceName = prefs.getString("tts_voice", null)
        _settings.value = TTSSettings(speed, pitch, voiceName)
    }

    private fun saveSettings() {
        prefs.edit()
            .putFloat("tts_speed", _settings.value.speed)
            .putFloat("tts_pitch", _settings.value.pitch)
            .putString("tts_voice", _settings.value.voiceName)
            .apply()
    }

    override fun onInit(status: Int) {
        Log.d("TTSManager", "onInit called with status: $status")
        Log.d("TTSManager", "Requested engine: $requestedEngine")
        Log.d("TTSManager", "Actual engine: ${tts?.defaultEngine}")

        if (status == TextToSpeech.SUCCESS) {
            val actualEngine = tts?.defaultEngine

            // Check if we got the engine we requested
            if (requestedEngine != null && actualEngine != requestedEngine) {
                Log.w("TTSManager", "Engine mismatch! Requested: $requestedEngine, Got: $actualEngine")
                Log.w("TTSManager", "This usually means the requested engine failed to initialize")
            }

            // For non-standard engines (Sherpa/Piper), skip setLanguage
            // These engines have language built into the voice model
            if (isNonStandardEngine()) {
                Log.d("TTSManager", "Non-standard TTS engine detected, skipping setLanguage")
                isInitialized = true
                loadAvailableVoices()
                setupUtteranceListener()
                restoreSavedVoice()
                Log.d("TTSManager", "TTS initialized successfully with ${tts?.defaultEngine}")
            } else {
                // Standard engine (Google, Samsung, etc.) - set language normally
                val result = tts?.setLanguage(Locale.US)

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTSManager", "Language not supported")
                    _state.value = TTSState.ERROR
                } else {
                    isInitialized = true
                    loadAvailableVoices()
                    setupUtteranceListener()
                    restoreSavedVoice()
                    Log.d("TTSManager", "TTS initialized successfully with ${tts?.defaultEngine}")
                }
            }

            // Debug: Log all available voices
            Log.d("TTSManager", "TTS Engine: ${tts?.defaultEngine}")
            Log.d("TTSManager", "Current Voice: ${tts?.voice?.name}")
            Log.d("TTSManager", "Available voices count: ${tts?.voices?.size ?: 0}")

        } else {
            Log.e("TTSManager", "TTS initialization failed with status: $status")
            _state.value = TTSState.ERROR
        }
    }

    private fun loadAvailableVoices() {
        tts?.voices?.let { voices ->
            // For non-standard engines, load ALL voices (they might not have standard locale info)
            val filteredVoices = if (isNonStandardEngine()) {
                voices.sortedBy { it.name }
            } else {
                // For standard engines, filter to English voices
                voices.filter {
                    it.locale.language == "en"
                }.sortedWith(
                    compareBy(
                        { it.isNetworkConnectionRequired },  // Offline voices first
                        { it.name }
                    )
                )
            }

            _availableVoices.value = filteredVoices.toList()
            Log.d("TTSManager", "Loaded ${filteredVoices.size} voices")

            // Log available voices for debugging
            filteredVoices.take(10).forEach { voice ->
                Log.d("TTSManager", "Voice: ${voice.name}, Locale: ${voice.locale}, Network: ${voice.isNetworkConnectionRequired}")
            }
        } ?: run {
            Log.w("TTSManager", "No voices available from TTS engine")
        }
    }

    private fun restoreSavedVoice() {
        val savedVoiceName = _settings.value.voiceName
        if (savedVoiceName != null) {
            val voice = _availableVoices.value.find { it.name == savedVoiceName }
            if (voice != null) {
                tts?.voice = voice
                _currentVoice.value = voice
                Log.d("TTSManager", "Restored voice: ${voice.name}")
            }
        } else {
            _currentVoice.value = tts?.voice
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTSManager", "Utterance started: $utteranceId")
                _state.value = TTSState.PLAYING
            }

            override fun onDone(utteranceId: String?) {
                Log.d("TTSManager", "Utterance done: $utteranceId")
                currentIndex++
                _currentSentenceIndex.value = currentIndex

                if (currentIndex < sentences.size) {
                    speakCurrentSentence()
                } else {
                    _state.value = TTSState.IDLE
                    _shouldAutoContinue.value = true
                    onChapterComplete?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e("TTSManager", "TTS error on utterance: $utteranceId")
                _state.value = TTSState.ERROR
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e("TTSManager", "TTS error on utterance: $utteranceId, errorCode: $errorCode")
                _state.value = TTSState.ERROR
            }
        })
    }

    fun speakText(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Log.e("TTSManager", "TTS not initialized")
            return
        }

        stop()
        _shouldAutoContinue.value = false

        sentences = text
            .replace("\n\n", ". ")
            .replace("\n", " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sentences.isEmpty()) {
            Log.e("TTSManager", "No sentences to speak")
            return
        }

        Log.d("TTSManager", "Speaking ${sentences.size} sentences with engine: ${tts?.defaultEngine}")

        currentIndex = 0
        _currentSentenceIndex.value = 0
        onChapterComplete = onComplete
        _state.value = TTSState.LOADING

        speakCurrentSentence()
    }

    fun autoContinueIfNeeded(text: String, onComplete: (() -> Unit)? = null) {
        if (_shouldAutoContinue.value) {
            Log.d("TTSManager", "Auto-continuing TTS for new chapter")
            speakText(text, onComplete)
        }
    }

    fun resetAutoContinue() {
        _shouldAutoContinue.value = false
    }

    private fun speakCurrentSentence() {
        if (currentIndex >= sentences.size) return

        val sentence = sentences[currentIndex]
        Log.d("TTSManager", "Speaking sentence $currentIndex: ${sentence.take(50)}...")

        tts?.setSpeechRate(_settings.value.speed)
        tts?.setPitch(_settings.value.pitch)

        val result = tts?.speak(
            sentence,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "sentence_$currentIndex"
        )

        Log.d("TTSManager", "speak() returned: $result (SUCCESS=0, ERROR=-1)")
    }

    fun pause() {
        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            _state.value = TTSState.PAUSED
        }
    }

    fun resume() {
        if (_state.value == TTSState.PAUSED) {
            speakCurrentSentence()
        }
    }

    fun stop() {
        tts?.stop()
        currentIndex = 0
        _currentSentenceIndex.value = 0
        sentences = emptyList()
        _state.value = TTSState.IDLE
        _shouldAutoContinue.value = false
    }

    fun skipToNext() {
        if (currentIndex < sentences.size - 1) {
            tts?.stop()
            currentIndex++
            _currentSentenceIndex.value = currentIndex
            speakCurrentSentence()
        }
    }

    fun skipToPrevious() {
        if (currentIndex > 0) {
            tts?.stop()
            currentIndex--
            _currentSentenceIndex.value = currentIndex
            speakCurrentSentence()
        }
    }

    fun setSpeed(speed: Float) {
        _settings.value = _settings.value.copy(speed = speed.coerceIn(0.5f, 2.0f))
        saveSettings()

        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    fun setPitch(pitch: Float) {
        _settings.value = _settings.value.copy(pitch = pitch.coerceIn(0.5f, 2.0f))
        saveSettings()
    }

    fun setVoice(voice: Voice) {
        tts?.voice = voice
        _currentVoice.value = voice
        _settings.value = _settings.value.copy(voiceName = voice.name)
        saveSettings()
        Log.d("TTSManager", "Voice set to: ${voice.name}")

        // If currently playing, restart with new voice
        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    fun getVoiceDisplayName(voice: Voice): String {
        // Create a user-friendly display name
        val locale = voice.locale
        val country = locale.displayCountry.ifBlank { locale.country }
        val quality = when {
            voice.name.contains("wavenet", ignoreCase = true) -> "WaveNet"
            voice.name.contains("neural", ignoreCase = true) -> "Neural"
            voice.name.contains("enhanced", ignoreCase = true) -> "Enhanced"
            voice.name.contains("local", ignoreCase = true) -> "Local"
            voice.name.contains("piper", ignoreCase = true) -> "Piper"
            voice.name.contains("lessac", ignoreCase = true) -> "Lessac"
            voice.name.contains("amy", ignoreCase = true) -> "Amy"
            else -> if (voice.isNetworkConnectionRequired) "Online" else "Offline"
        }

        val gender = when {
            voice.name.contains("female", ignoreCase = true) -> "♀"
            voice.name.contains("male", ignoreCase = true) -> "♂"
            voice.name.contains("-f-", ignoreCase = true) -> "♀"
            voice.name.contains("-m-", ignoreCase = true) -> "♂"
            else -> ""
        }

        return if (country.isNotBlank()) {
            "$country $gender ($quality)"
        } else {
            "${voice.name.take(20)} ($quality)"
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isPlaying(): Boolean = _state.value == TTSState.PLAYING

    fun getSentenceCount(): Int = sentences.size

    /**
     * Get the currently active TTS engine name
     */
    fun getCurrentEngineName(): String? = tts?.defaultEngine
}