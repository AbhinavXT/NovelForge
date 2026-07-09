package com.abhinavxt.novelforge.data.tts

/**
 * Abstraction over any TTS synthesis backend.
 *
 * TTSManager owns the sentence queue, paragraph tracking, pause/resume,
 * and playback sequencing. It delegates the actual "speak this one sentence"
 * work to whichever TTSEngine is active.
 *
 * Implementations:
 *  - GoogleTTSEngine  → wraps Android's TextToSpeech API (system voices)
 *  - SherpaOnnxEngine → embedded neural TTS via Sherpa-ONNX library + AudioTrack
 */
interface TTSEngine {

    /** Human-readable name shown in the engine picker UI. */
    val displayName: String

    /** Unique identifier persisted in SharedPreferences. */
    val engineId: String

    /** True once the engine is ready to synthesize. */
    val isReady: Boolean

    /**
     * Initialise the engine. Called once when selected.
     * @param onReady  fires when the engine can accept [speak] calls.
     * @param onError  fires if initialisation fails (message for logging).
     */
    fun initialize(onReady: () -> Unit, onError: (String) -> Unit)

    /**
     * Speak a single sentence (or text chunk).
     *
     * The engine MUST call exactly one of the three callbacks when finished:
     *  - [onDone]  → utterance completed successfully
     *  - [onError] → something went wrong
     *  - (neither, if [stop] is called before completion)
     *
     * @param text         The sentence to speak.
     * @param utteranceId  Unique ID for tracking (e.g. "sentence_42").
     * @param speed        Speech rate multiplier (0.5–2.0, 1.0 = normal).
     * @param pitch        Pitch multiplier (0.5–2.0, 1.0 = normal). May be
     *                     ignored by engines that don't support pitch control.
     * @param volume       Volume multiplier (0.0–1.0).
     * @param onStart      Called when audio actually begins playing.
     * @param onDone       Called when the utterance finishes playing.
     * @param onError      Called on synthesis/playback failure.
     */
    fun speak(
        text: String,
        utteranceId: String,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    )

    /** Stop any in-progress speech immediately. */
    fun stop()

    /** Release all resources. After this the engine cannot be re-used. */
    fun shutdown()

    /**
     * Return available voice names for this engine.
     * Google TTS → system Voice objects mapped to display strings.
     * Sherpa-ONNX → downloaded model names (e.g. "piper-amy-low").
     */
    fun getAvailableVoices(): List<VoiceInfo>

    /**
     * Select a voice by its id.
     * For Google this is the Voice.name; for Sherpa it's the model folder name.
     */
    fun setVoice(voiceId: String)

    /** Currently selected voice id, or null. */
    fun getCurrentVoiceId(): String?
}

/**
 * Lightweight descriptor shown in the voice-picker UI.
 */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val isDownloaded: Boolean = true,   // always true for Google; may be false for Sherpa models not yet on disk
    val engineId: String = ""
)