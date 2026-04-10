package com.abhinavxt.novelreader.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import com.abhinavxt.novelreader.data.tts.GoogleTTSEngine
import com.abhinavxt.novelreader.data.tts.SherpaOnnxEngine
import com.abhinavxt.novelreader.data.tts.TTSEngine
import com.abhinavxt.novelreader.data.tts.TTSModelManager
import com.abhinavxt.novelreader.data.tts.VoiceInfo
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
    val sentencePauseMs: Long = 0L,
    val paragraphPauseMs: Long = 0L,
    val volume: Float = 1.0f,
    val engineId: String = GoogleTTSEngine.ENGINE_ID  // NEW: persisted engine choice
)

private data class TextSegment(
    val text: String,
    val paragraphIndex: Int,
    val sentenceIndexInParagraph: Int,
    val isParagraphEnd: Boolean = false
)

/**
 * Orchestrates TTS playback: sentence splitting, sequencing, pauses,
 * paragraph tracking, auto-continue, and foreground service.
 *
 * Delegates actual speech synthesis to the active [TTSEngine].
 *
 * ## Engine switching:
 * Call [switchEngine] to change between Google TTS and Sherpa-ONNX.
 * The engine choice is persisted in SharedPreferences.
 */
class TTSManager(private val context: Context) {

    /** Set by NovelReaderApplication after both are initialized */
    var pronunciationManager: PronunciationManager? = null

    private val prefs = context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var pauseRunnable: Runnable? = null

    // ── Engines ──────────────────────────────────────────────────

    val googleEngine = GoogleTTSEngine(context)
    /** Public access for AudioExporter and pre-generation */
    val sherpaEngine: SherpaOnnxEngine by lazy {
        val modelsDir = File(context.filesDir, "tts_models")
        modelsDir.mkdirs()
        SherpaOnnxEngine(modelsDir)
    }
    val modelManager: TTSModelManager by lazy { TTSModelManager(context) }

    private var activeEngine: TTSEngine = googleEngine

    // ── Observable state ─────────────────────────────────────────

    private val _state = MutableStateFlow(TTSState.IDLE)
    val state: StateFlow<TTSState> = _state.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _currentParagraphIndex = MutableStateFlow(0)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex.asStateFlow()

    private val _currentSentenceInParagraph = MutableStateFlow(0)
    val currentSentenceInParagraph: StateFlow<Int> = _currentSentenceInParagraph.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<VoiceInfo>> = _availableVoices.asStateFlow()

    private val _currentVoice = MutableStateFlow<VoiceInfo?>(null)
    val currentVoice: StateFlow<VoiceInfo?> = _currentVoice.asStateFlow()

    private val _settings = MutableStateFlow(TTSSettings())
    val settings: StateFlow<TTSSettings> = _settings.asStateFlow()

    private val _shouldAutoContinue = MutableStateFlow(false)
    val shouldAutoContinue: StateFlow<Boolean> = _shouldAutoContinue.asStateFlow()

    private val _activeEngineId = MutableStateFlow(GoogleTTSEngine.ENGINE_ID)
    val activeEngineId: StateFlow<String> = _activeEngineId.asStateFlow()

    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    // ── Sentence tracking ────────────────────────────────────────

    private var segments: List<TextSegment> = emptyList()
    private var currentIndex = 0
    private var onChapterComplete: (() -> Unit)? = null

    // Now-playing metadata for notification
    private var nowPlayingNovelTitle: String = ""
    private var nowPlayingChapterTitle: String = ""

    // ── Backward compatibility ───────────────────────────────────
    // These expose the Android Voice objects for the existing voice picker UI.
    // They only work when Google TTS is the active engine.

    private val _androidVoices = MutableStateFlow<List<Voice>>(emptyList())

    /** Android Voice list — only populated when Google TTS is active. */
    @Deprecated("Use availableVoices instead for engine-agnostic voice list")
    val androidVoicesLegacy: StateFlow<List<Voice>> = _androidVoices.asStateFlow()

    // ── Init ─────────────────────────────────────────────────────

    init {
        loadSavedSettings()

        val savedEngineId = _settings.value.engineId

        // Always initialize Google as default/fallback
        googleEngine.initialize(
            onReady = {
                Logger.d("TTSManager", "Google TTS ready")
                if (savedEngineId == GoogleTTSEngine.ENGINE_ID) {
                    activateEngine(googleEngine)
                } else {
                    // Even if Google isn't the active engine, refresh so
                    // its voices appear in the picker alongside Sherpa voices
                    refreshVoiceList()
                }
            },
            onError = { error ->
                Logger.e("TTSManager", "Google TTS init failed: $error")
                if (savedEngineId == GoogleTTSEngine.ENGINE_ID) {
                    _state.value = TTSState.ERROR
                }
            }
        )

        // If user had Sherpa selected, try to initialize it too
        if (savedEngineId == SherpaOnnxEngine.ENGINE_ID) {
            sherpaEngine.initialize(
                onReady = {
                    Logger.d("TTSManager", "Sherpa-ONNX ready")
                    activateEngine(sherpaEngine)
                },
                onError = { error ->
                    Logger.w("TTSManager", "Sherpa-ONNX init failed: $error, falling back to Google")
                    // Fall back to Google
                    activateEngine(googleEngine)
                }
            )
        }
    }

    // ── Engine switching ─────────────────────────────────────────

    /**
     * Switch to a different TTS engine.
     * Stops any current playback first.
     */
    fun switchEngine(engineId: String, onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (engineId == _activeEngineId.value && activeEngine.isReady) {
            onReady()
            return
        }

        stop()

        val engine = when (engineId) {
            GoogleTTSEngine.ENGINE_ID -> googleEngine
            SherpaOnnxEngine.ENGINE_ID -> sherpaEngine
            else -> {
                onError("Unknown engine: $engineId")
                return
            }
        }

        if (engine.isReady) {
            activateEngine(engine)
            _settings.value = _settings.value.copy(engineId = engineId)
            saveSettings()
            onReady()
        } else {
            _state.value = TTSState.LOADING
            engine.initialize(
                onReady = {
                    activateEngine(engine)
                    _settings.value = _settings.value.copy(engineId = engineId)
                    saveSettings()
                    onReady()
                },
                onError = { error ->
                    Logger.e("TTSManager", "Engine $engineId init failed: $error")
                    _state.value = TTSState.ERROR
                    onError(error)
                }
            )
        }
    }

    private fun activateEngine(engine: TTSEngine) {
        activeEngine = engine
        _activeEngineId.value = engine.engineId
        _engineReady.value = true

        // Refresh voice list
        refreshVoiceList()
        Logger.d("TTSManager", "Active engine: ${engine.displayName}")
    }

    fun refreshVoiceList() {
        // Aggregate voices from ALL engines so the picker shows everything
        val allVoices = mutableListOf<VoiceInfo>()

        // Google voices — always include. getAvailableVoices() returns from
        // cachedVoices which stays valid even if the engine isn't "ready" for playback.
        val googleVoices = googleEngine.getAvailableVoices()
        allVoices.addAll(googleVoices)
        Logger.d("TTSManager", "Google voices: ${googleVoices.size}")

        // Sherpa voices — scan downloaded models even if engine isn't active
        val sherpaVoices = sherpaEngine.getAvailableVoices()
        allVoices.addAll(sherpaVoices)
        Logger.d("TTSManager", "Sherpa voices: ${sherpaVoices.size}")

        Logger.d("TTSManager", "Total voices: ${allVoices.size}")
        _availableVoices.value = allVoices

        // Update current voice
        val currentId = activeEngine.getCurrentVoiceId()
        _currentVoice.value = allVoices.find { it.id == currentId }

        // Legacy Android Voice support
        if (activeEngine is GoogleTTSEngine) {
            _androidVoices.value = (activeEngine as GoogleTTSEngine).getAndroidVoices()
        } else {
            _androidVoices.value = emptyList()
        }
    }

    fun getAvailableEngines(): List<EngineOption> {
        val hasSherpaModels = modelManager.getDownloadedModelIds().isNotEmpty()
        return listOf(
            EngineOption(
                id = GoogleTTSEngine.ENGINE_ID,
                name = "System TTS (Google)",
                description = "Built-in Android voices",
                isAvailable = true
            ),
            EngineOption(
                id = SherpaOnnxEngine.ENGINE_ID,
                name = "On-Device Neural TTS",
                description = if (hasSherpaModels) "Piper, KittenTTS, Kokoro models"
                else "Download a model to enable",
                isAvailable = hasSherpaModels
            )
        )
    }

    // ── Settings persistence ─────────────────────────────────────

    private fun loadSavedSettings() {
        val speed = prefs.getFloat("tts_speed", 1.0f)
        val pitch = prefs.getFloat("tts_pitch", 1.0f)
        val voiceName = prefs.getString("tts_voice", null)
        val sentencePause = prefs.getLong("tts_sentence_pause", 0L)
        val paragraphPause = prefs.getLong("tts_paragraph_pause", 0L)
        val volume = prefs.getFloat("tts_volume", 1.0f)
        val engineId = prefs.getString("tts_engine", GoogleTTSEngine.ENGINE_ID)
            ?: GoogleTTSEngine.ENGINE_ID

        _settings.value = TTSSettings(
            speed = speed,
            pitch = pitch,
            voiceName = voiceName,
            sentencePauseMs = sentencePause,
            paragraphPauseMs = paragraphPause,
            volume = volume,
            engineId = engineId
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
            .putString("tts_engine", _settings.value.engineId)
            .apply()
    }

    // ── Text parsing (unchanged from original) ───────────────────

    private fun parseTextIntoSegments(text: String): List<TextSegment> {
        val result = mutableListOf<TextSegment>()

        val paragraphs = text.split(Regex("\n\n+|\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        paragraphs.forEachIndexed { pIndex, paragraph ->
            val sentences = paragraph
                .split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            sentences.forEachIndexed { sIndex, sentence ->
                val isLastSentenceInParagraph = sIndex == sentences.size - 1
                val isLastParagraph = pIndex == paragraphs.size - 1

                result.add(
                    TextSegment(
                        text = sentence,
                        paragraphIndex = pIndex,
                        sentenceIndexInParagraph = sIndex,
                        isParagraphEnd = isLastSentenceInParagraph && !isLastParagraph
                    )
                )
            }
        }

        return result
    }

    // ── Playback control ─────────────────────────────────────────

    fun speakText(
        text: String,
        startFromParagraph: Int = 0,
        novelTitle: String = "",
        chapterTitle: String = "",
        onComplete: (() -> Unit)? = null
    ) {
        if (!activeEngine.isReady) {
            Logger.e("TTSManager", "Active engine not ready")
            return
        }

        stop()
        _shouldAutoContinue.value = false

        // Save metadata for notification
        if (novelTitle.isNotBlank()) nowPlayingNovelTitle = novelTitle
        if (chapterTitle.isNotBlank()) nowPlayingChapterTitle = chapterTitle

        segments = parseTextIntoSegments(text)

        if (segments.isEmpty()) {
            Logger.e("TTSManager", "No sentences to speak")
            return
        }

        val startIndex = if (startFromParagraph > 0) {
            segments.indexOfFirst { it.paragraphIndex >= startFromParagraph }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }

        Logger.d("TTSManager", "Speaking ${segments.size} segments from paragraph $startFromParagraph (index $startIndex)")

        currentIndex = startIndex
        _currentSentenceIndex.value = currentIndex

        val startSegment = segments.getOrNull(currentIndex)
        _currentParagraphIndex.value = startSegment?.paragraphIndex ?: 0
        _currentSentenceInParagraph.value = startSegment?.sentenceIndexInParagraph ?: 0

        onChapterComplete = onComplete
        _state.value = TTSState.LOADING

        TTSForegroundService.start(context, nowPlayingNovelTitle.ifBlank { "Novel Reader" })

        speakCurrentSentence()
    }

    fun autoContinueIfNeeded(
        text: String,
        startFromParagraph: Int = 0,
        novelTitle: String = "",
        chapterTitle: String = "",
        onComplete: (() -> Unit)? = null
    ) {
        if (_shouldAutoContinue.value) {
            Logger.d("TTSManager", "Auto-continuing TTS for new chapter")
            speakText(text, startFromParagraph, novelTitle, chapterTitle, onComplete)
        }
    }

    fun resetAutoContinue() {
        _shouldAutoContinue.value = false
    }

    private fun speakCurrentSentence() {
        if (currentIndex >= segments.size) return

        val segment = segments[currentIndex]
        // Apply pronunciation dictionary substitutions
        val textToSpeak = pronunciationManager?.applyReplacements(segment.text) ?: segment.text
        Logger.d("TTSManager", "Speaking sentence $currentIndex: ${segment.text.take(50)}...")

        val s = _settings.value

        activeEngine.speak(
            text = textToSpeak,
            utteranceId = "sentence_$currentIndex",
            speed = s.speed,
            pitch = s.pitch,
            volume = s.volume,
            onStart = {
                _state.value = TTSState.PLAYING
                // Update notification with progress
                val progress = "${currentIndex + 1}/${segments.size}"
                val subtitle = if (nowPlayingChapterTitle.isNotBlank()) {
                    "$nowPlayingChapterTitle  •  $progress"
                } else {
                    "Sentence $progress"
                }
                TTSForegroundService.updateNotification(
                    context,
                    nowPlayingNovelTitle.ifBlank { "Novel Reader" },
                    subtitle
                )
                // Pre-generate the next sentence while this one plays
                pregenerateNextSentences()
            },
            onDone = {
                onUtteranceDone()
            },
            onError = { error ->
                Logger.e("TTSManager", "Utterance error: $error")
                _state.value = TTSState.ERROR
            }
        )
    }

    /**
     * Pre-generate upcoming sentences' audio while current one plays.
     * Only works with SherpaOnnxEngine (on-device TTS). Google TTS handles
     * its own buffering internally.
     */
    private fun pregenerateNextSentences() {
        val sherpaEngine = activeEngine as? SherpaOnnxEngine ?: return
        val count = sherpaEngine.getLookAheadCount()
        val startIdx = currentIndex + 1
        val endIdx = minOf(startIdx + count, segments.size)

        if (startIdx >= segments.size) return

        val upcomingTexts = segments.subList(startIdx, endIdx).map { it.text }
        val s = _settings.value

        sherpaEngine.pregenerateBatchAsync(
            sentences = upcomingTexts,
            speed = s.speed
        )
    }

    private fun onUtteranceDone() {
        val currentSegment = segments.getOrNull(currentIndex)
        currentIndex++
        _currentSentenceIndex.value = currentIndex

        if (currentIndex < segments.size) {
            // Update paragraph tracking
            val nextSegment = segments[currentIndex]
            _currentParagraphIndex.value = nextSegment.paragraphIndex
            _currentSentenceInParagraph.value = nextSegment.sentenceIndexInParagraph

            // Apply pause between sentences/paragraphs
            val pauseMs = if (currentSegment?.isParagraphEnd == true) {
                _settings.value.paragraphPauseMs
            } else {
                _settings.value.sentencePauseMs
            }

            if (pauseMs > 0) {
                pauseRunnable = Runnable {
                    if (_state.value == TTSState.PLAYING) {
                        speakCurrentSentence()
                    }
                }
                handler.postDelayed(pauseRunnable!!, pauseMs)
            } else {
                speakCurrentSentence()
            }
        } else {
            // Chapter complete
            _state.value = TTSState.IDLE
            _shouldAutoContinue.value = true
            onChapterComplete?.invoke()
        }
    }

    private fun cancelPendingPause() {
        pauseRunnable?.let { handler.removeCallbacks(it) }
        pauseRunnable = null
    }

    fun pause() {
        if (_state.value == TTSState.PLAYING) {
            cancelPendingPause()
            activeEngine.stop()
            _state.value = TTSState.PAUSED
        }
    }

    fun resume() {
        if (_state.value == TTSState.PAUSED) {
            speakCurrentSentence()
        }
    }

    fun stop() {
        cancelPendingPause()
        activeEngine.stop()
        currentIndex = 0
        _currentSentenceIndex.value = 0
        _currentParagraphIndex.value = 0
        _currentSentenceInParagraph.value = 0
        segments = emptyList()
        _state.value = TTSState.IDLE
        _shouldAutoContinue.value = false
        nowPlayingNovelTitle = ""
        nowPlayingChapterTitle = ""

        TTSForegroundService.stop(context)
    }

    fun skipToNext() {
        if (currentIndex < segments.size - 1) {
            cancelPendingPause()
            activeEngine.stop()
            currentIndex++
            _currentSentenceIndex.value = currentIndex

            val segment = segments[currentIndex]
            _currentParagraphIndex.value = segment.paragraphIndex
            _currentSentenceInParagraph.value = segment.sentenceIndexInParagraph

            speakCurrentSentence()
        }
    }

    fun skipToPrevious() {
        if (currentIndex > 0) {
            cancelPendingPause()
            activeEngine.stop()
            currentIndex--
            _currentSentenceIndex.value = currentIndex

            val segment = segments[currentIndex]
            _currentParagraphIndex.value = segment.paragraphIndex
            _currentSentenceInParagraph.value = segment.sentenceIndexInParagraph

            speakCurrentSentence()
        }
    }

    // ── Settings setters ─────────────────────────────────────────

    fun setSpeed(speed: Float) {
        _settings.value = _settings.value.copy(speed = speed.coerceIn(0.5f, 2.0f))
        saveSettings()

        if (_state.value == TTSState.PLAYING) {
            activeEngine.stop()
            speakCurrentSentence()
        }
    }

    fun setPitch(pitch: Float) {
        _settings.value = _settings.value.copy(pitch = pitch.coerceIn(0.5f, 2.0f))
        saveSettings()

        if (_state.value == TTSState.PLAYING) {
            activeEngine.stop()
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
            activeEngine.stop()
            speakCurrentSentence()
        }
    }

    // ── Voice selection ──────────────────────────────────────────

    fun setVoice(voiceInfo: VoiceInfo) {
        // If the voice belongs to a different engine, switch first
        if (voiceInfo.engineId.isNotBlank() && voiceInfo.engineId != _activeEngineId.value) {
            Logger.d("TTSManager", "Voice belongs to ${voiceInfo.engineId}, switching engine…")
            switchEngine(
                engineId = voiceInfo.engineId,
                onReady = {
                    applyVoice(voiceInfo)
                },
                onError = { error ->
                    Logger.e("TTSManager", "Engine switch failed: $error")
                }
            )
        } else {
            applyVoice(voiceInfo)
        }
    }

    private fun applyVoice(voiceInfo: VoiceInfo) {
        activeEngine.setVoice(voiceInfo.id)
        _currentVoice.value = voiceInfo
        _settings.value = _settings.value.copy(voiceName = voiceInfo.id)
        saveSettings()
        Logger.d("TTSManager", "Voice set to: ${voiceInfo.displayName}")

        if (_state.value == TTSState.PLAYING) {
            activeEngine.stop()
            speakCurrentSentence()
        }
    }

    /**
     * Legacy voice setter for backward compatibility with existing UI
     * that passes Android [Voice] objects directly.
     */
    fun setVoice(voice: Voice) {
        if (activeEngine is GoogleTTSEngine) {
            (activeEngine as GoogleTTSEngine).setAndroidVoice(voice)
            _settings.value = _settings.value.copy(voiceName = voice.name)
            saveSettings()

            val displayName = (activeEngine as GoogleTTSEngine).getVoiceDisplayName(voice)
            _currentVoice.value = VoiceInfo(
                id = voice.name,
                displayName = displayName,
                isDownloaded = true,
                engineId = GoogleTTSEngine.ENGINE_ID
            )

            if (_state.value == TTSState.PLAYING) {
                activeEngine.stop()
                speakCurrentSentence()
            }
        }
    }

    /**
     * Legacy helper for existing voice picker that uses [Voice] display names.
     */
    fun getVoiceDisplayName(voice: Voice): String {
        return if (activeEngine is GoogleTTSEngine) {
            (activeEngine as GoogleTTSEngine).getVoiceDisplayName(voice)
        } else {
            voice.name
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────

    fun shutdown() {
        cancelPendingPause()
        googleEngine.shutdown()
        if (sherpaEngine.isReady) {
            sherpaEngine.shutdown()
        }
        TTSForegroundService.stop(context)
    }

    // ── Query helpers ────────────────────────────────────────────

    fun isPlaying(): Boolean = _state.value == TTSState.PLAYING

    fun getSentenceCount(): Int = segments.size

    fun getCurrentEngineName(): String = activeEngine.displayName
}

/**
 * Engine option shown in the engine picker UI.
 */
data class EngineOption(
    val id: String,
    val name: String,
    val description: String,
    val isAvailable: Boolean
)