package com.example.novelreader.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.novelreader.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val voiceName: String? = null,
    val sentencePauseMs: Long = 300L,      // Pause after each sentence
    val paragraphPauseMs: Long = 800L,     // Pause after paragraphs
    val volume: Float = 1.0f               // Volume 0.0 - 1.0
)

// Represents a text segment with its type
private data class TextSegment(
    val text: String,
    val isParagraphEnd: Boolean = false
)

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var requestedEngine: String? = null

    private val prefs = context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var pauseJob: Job? = null

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

    private var segments: List<TextSegment> = emptyList()
    private var currentIndex = 0
    private var onChapterComplete: (() -> Unit)? = null

    init {
        requestedEngine = getDefaultTtsEngine()
        Logger.d("TTSManager", "Default TTS engine from settings: $requestedEngine")

        tts = if (requestedEngine != null) {
            TextToSpeech(context, this, requestedEngine)
        } else {
            TextToSpeech(context, this)
        }

        loadSavedSettings()
    }

    private fun getDefaultTtsEngine(): String? {
        return try {
            val resolver = context.contentResolver
            android.provider.Settings.Secure.getString(
                resolver,
                "tts_default_synth"
            )
        } catch (e: Exception) {
            Logger.e("TTSManager", "Error getting default TTS engine", e)
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

    private fun loadSavedSettings() {
        val speed = prefs.getFloat("tts_speed", 1.0f)
        val pitch = prefs.getFloat("tts_pitch", 1.0f)
        val voiceName = prefs.getString("tts_voice", null)
        val sentencePause = prefs.getLong("tts_sentence_pause", 300L)
        val paragraphPause = prefs.getLong("tts_paragraph_pause", 800L)
        val volume = prefs.getFloat("tts_volume", 1.0f)

        _settings.value = TTSSettings(
            speed = speed,
            pitch = pitch,
            voiceName = voiceName,
            sentencePauseMs = sentencePause,
            paragraphPauseMs = paragraphPause,
            volume = volume
        )
    }

    private fun saveSettings() {
        prefs.edit()
            .putFloat("tts_speed", _settings.value.speed)
            .putFloat("tts_pitch", _settings.value.pitch)
            .putString("tts_voice", _settings.value.voiceName)
            .putLong("tts_sentence_pause", _settings.value.sentencePauseMs)
            .putLong("tts_paragraph_pause", _settings.value.paragraphPauseMs)
            .putFloat("tts_volume", _settings.value.volume)
            .apply()
    }

    override fun onInit(status: Int) {
        Logger.d("TTSManager", "onInit called with status: $status")
        Logger.d("TTSManager", "Requested engine: $requestedEngine")
        Logger.d("TTSManager", "Actual engine: ${tts?.defaultEngine}")

        if (status == TextToSpeech.SUCCESS) {
            val actualEngine = tts?.defaultEngine

            if (requestedEngine != null && actualEngine != requestedEngine) {
                Logger.w("TTSManager", "Engine mismatch! Requested: $requestedEngine, Got: $actualEngine")
            }

            if (isNonStandardEngine()) {
                Logger.d("TTSManager", "Non-standard TTS engine detected, skipping setLanguage")
                isInitialized = true
                loadAvailableVoices()
                setupUtteranceListener()
                restoreSavedVoice()
                Logger.d("TTSManager", "TTS initialized successfully with ${tts?.defaultEngine}")
            } else {
                val result = tts?.setLanguage(Locale.US)

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Logger.e("TTSManager", "Language not supported")
                    _state.value = TTSState.ERROR
                } else {
                    isInitialized = true
                    loadAvailableVoices()
                    setupUtteranceListener()
                    restoreSavedVoice()
                    Logger.d("TTSManager", "TTS initialized successfully with ${tts?.defaultEngine}")
                }
            }

            Logger.d("TTSManager", "TTS Engine: ${tts?.defaultEngine}")
            Logger.d("TTSManager", "Current Voice: ${tts?.voice?.name}")
            Logger.d("TTSManager", "Available voices count: ${tts?.voices?.size ?: 0}")

        } else {
            Logger.e("TTSManager", "TTS initialization failed with status: $status")
            _state.value = TTSState.ERROR
        }
    }

    private fun loadAvailableVoices() {
        tts?.voices?.let { voices ->
            val filteredVoices = if (isNonStandardEngine()) {
                voices.sortedBy { it.name }
            } else {
                voices.filter {
                    it.locale.language == "en"
                }.sortedWith(
                    compareBy(
                        { it.isNetworkConnectionRequired },
                        { it.name }
                    )
                )
            }

            _availableVoices.value = filteredVoices.toList()
            Logger.d("TTSManager", "Loaded ${filteredVoices.size} voices")

            filteredVoices.take(10).forEach { voice ->
                Logger.d("TTSManager", "Voice: ${voice.name}, Locale: ${voice.locale}, Network: ${voice.isNetworkConnectionRequired}")
            }
        } ?: run {
            Logger.w("TTSManager", "No voices available from TTS engine")
        }
    }

    private fun restoreSavedVoice() {
        val savedVoiceName = _settings.value.voiceName
        if (savedVoiceName != null) {
            val voice = _availableVoices.value.find { it.name == savedVoiceName }
            if (voice != null) {
                tts?.voice = voice
                _currentVoice.value = voice
                Logger.d("TTSManager", "Restored voice: ${voice.name}")
            }
        } else {
            _currentVoice.value = tts?.voice
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Logger.d("TTSManager", "Utterance started: $utteranceId")
                _state.value = TTSState.PLAYING
            }

            override fun onDone(utteranceId: String?) {
                Logger.d("TTSManager", "Utterance done: $utteranceId")

                val currentSegment = segments.getOrNull(currentIndex)
                currentIndex++
                _currentSentenceIndex.value = currentIndex

                if (currentIndex < segments.size) {
                    // Determine pause duration
                    val pauseMs = if (currentSegment?.isParagraphEnd == true) {
                        _settings.value.paragraphPauseMs
                    } else {
                        _settings.value.sentencePauseMs
                    }

                    // Apply pause before next sentence
                    if (pauseMs > 0) {
                        pauseJob = scope.launch {
                            delay(pauseMs)
                            if (_state.value == TTSState.PLAYING) {
                                speakCurrentSentence()
                            }
                        }
                    } else {
                        speakCurrentSentence()
                    }
                } else {
                    _state.value = TTSState.IDLE
                    _shouldAutoContinue.value = true
                    onChapterComplete?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                Logger.e("TTSManager", "TTS error on utterance: $utteranceId")
                _state.value = TTSState.ERROR
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Logger.e("TTSManager", "TTS error on utterance: $utteranceId, errorCode: $errorCode")
                _state.value = TTSState.ERROR
            }
        })
    }

    /**
     * Parse text into segments, tracking paragraph endings
     */
    private fun parseTextIntoSegments(text: String): List<TextSegment> {
        val result = mutableListOf<TextSegment>()

        // Split by paragraphs first (double newline or single newline)
        val paragraphs = text.split(Regex("\n\n+|\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        paragraphs.forEachIndexed { pIndex, paragraph ->
            // Split paragraph into sentences
            val sentences = paragraph
                .split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            sentences.forEachIndexed { sIndex, sentence ->
                val isLastSentenceInParagraph = sIndex == sentences.size - 1
                val isLastParagraph = pIndex == paragraphs.size - 1

                result.add(TextSegment(
                    text = sentence,
                    isParagraphEnd = isLastSentenceInParagraph && !isLastParagraph
                ))
            }
        }

        return result
    }

    fun speakText(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Logger.e("TTSManager", "TTS not initialized")
            return
        }

        stop()
        _shouldAutoContinue.value = false

        segments = parseTextIntoSegments(text)

        if (segments.isEmpty()) {
            Logger.e("TTSManager", "No sentences to speak")
            return
        }

        Logger.d("TTSManager", "Speaking ${segments.size} segments with engine: ${tts?.defaultEngine}")

        currentIndex = 0
        _currentSentenceIndex.value = 0
        onChapterComplete = onComplete
        _state.value = TTSState.LOADING

        speakCurrentSentence()
    }

    fun autoContinueIfNeeded(text: String, onComplete: (() -> Unit)? = null) {
        if (_shouldAutoContinue.value) {
            Logger.d("TTSManager", "Auto-continuing TTS for new chapter")
            speakText(text, onComplete)
        }
    }

    fun resetAutoContinue() {
        _shouldAutoContinue.value = false
    }

    private fun speakCurrentSentence() {
        if (currentIndex >= segments.size) return

        val segment = segments[currentIndex]
        Logger.d("TTSManager", "Speaking sentence $currentIndex: ${segment.text.take(50)}...")

        tts?.setSpeechRate(_settings.value.speed)
        tts?.setPitch(_settings.value.pitch)

        // Set volume using Bundle
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, _settings.value.volume)

        val result = tts?.speak(
            segment.text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "sentence_$currentIndex"
        )

        Logger.d("TTSManager", "speak() returned: $result (SUCCESS=0, ERROR=-1)")
    }

    fun pause() {
        if (_state.value == TTSState.PLAYING) {
            pauseJob?.cancel()
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
        pauseJob?.cancel()
        tts?.stop()
        currentIndex = 0
        _currentSentenceIndex.value = 0
        segments = emptyList()
        _state.value = TTSState.IDLE
        _shouldAutoContinue.value = false
    }

    fun skipToNext() {
        if (currentIndex < segments.size - 1) {
            pauseJob?.cancel()
            tts?.stop()
            currentIndex++
            _currentSentenceIndex.value = currentIndex
            speakCurrentSentence()
        }
    }

    fun skipToPrevious() {
        if (currentIndex > 0) {
            pauseJob?.cancel()
            tts?.stop()
            currentIndex--
            _currentSentenceIndex.value = currentIndex
            speakCurrentSentence()
        }
    }

    // ============ Settings Methods ============

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

        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    fun setSentencePause(pauseMs: Long) {
        _settings.value = _settings.value.copy(sentencePauseMs = pauseMs.coerceIn(0L, 2000L))
        saveSettings()
    }

    fun setParagraphPause(pauseMs: Long) {
        _settings.value = _settings.value.copy(paragraphPauseMs = pauseMs.coerceIn(0L, 3000L))
        saveSettings()
    }

    fun setVolume(volume: Float) {
        _settings.value = _settings.value.copy(volume = volume.coerceIn(0f, 1f))
        saveSettings()

        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    fun setVoice(voice: Voice) {
        tts?.voice = voice
        _currentVoice.value = voice
        _settings.value = _settings.value.copy(voiceName = voice.name)
        saveSettings()
        Logger.d("TTSManager", "Voice set to: ${voice.name}")

        if (_state.value == TTSState.PLAYING) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    fun getVoiceDisplayName(voice: Voice): String {
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
        pauseJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isPlaying(): Boolean = _state.value == TTSState.PLAYING

    fun getSentenceCount(): Int = segments.size

    fun getCurrentEngineName(): String? = tts?.defaultEngine
}