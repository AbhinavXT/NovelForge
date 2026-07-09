package com.abhinavxt.novelforge.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.abhinavxt.novelforge.util.Logger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS engine that uses Sherpa-ONNX embedded library for on-device neural TTS.
 *
 * Flow: Text → Sherpa-ONNX OfflineTts.generate() → PCM FloatArray → AudioTrack
 *
 * Supports multiple model backends through Sherpa-ONNX:
 *  - Piper (VITS)   — most mature, hundreds of voices, 20-60 MB each
 *  - KittenTTS       — lightweight (~25 MB), fast on any device
 *  - Kokoro          — high quality, ~350 MB, best on flagship phones
 *
 * Models are stored in: {app_internal_storage}/tts_models/{model_folder_name}/
 *
 * ## Setup required:
 * 1. Copy Sherpa-ONNX jniLibs (.so files) and Kotlin API files into the project
 * 2. Download at least one model via TTSModelManager
 * 3. Call [initialize] with the model path set via [loadModel]
 */
class SherpaOnnxEngine(
    private val modelsDir: File  // e.g. context.filesDir / "tts_models"
) : TTSEngine {

    companion object {
        const val ENGINE_ID = "sherpa_onnx"
        private const val TAG = "SherpaOnnxEngine"
    }

    override val displayName: String = "On-Device Neural TTS"
    override val engineId: String = ENGINE_ID
    override var isReady: Boolean = false
        private set

    private var ttsWrapper: SherpaOnnxWrapper? = null

    private var currentModelId: String? = null
    private var currentSpeakerId: Int = 0
    private var sampleRate: Int = 22050

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private var synthesisJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Per-utterance state
    @Volatile private var isStopped = false

    // ── Look-ahead buffer ───────────────────────────────────────
    // Pre-generates the next sentence's audio while current one plays,
    // eliminating the gap between sentences.

    private val audioCache = java.util.concurrent.ConcurrentHashMap<String, GeneratedAudioData>()
    private var pregenerateJob: Job? = null
    private val lookAheadCount = 3  // pre-generate this many sentences ahead
    private val maxCacheSize = 5
    private val generateMutex = Mutex()  // native tts.generate() is NOT thread-safe

    /**
     * Pre-generate audio for multiple upcoming sentences. Called by TTSManager
     * when the current sentence starts playing. Generates sequentially
     * (JNI not thread-safe) but runs on a background coroutine.
     */
    fun pregenerateBatchAsync(
        sentences: List<String>,
        speakerId: Int = currentSpeakerId,
        speed: Float = 1.0f
    ) {
        if (sentences.isEmpty() || ttsWrapper == null) return

        pregenerateJob?.cancel()
        pregenerateJob = scope.launch {
            for (text in sentences) {
                if (!isActive || isStopped) break

                val cacheKey = buildCacheKey(text, speakerId, speed)
                if (audioCache.containsKey(cacheKey)) continue  // already cached

                // tryLock: yield to speak() if it needs the mutex
                if (!generateMutex.tryLock()) {
                    Logger.d(TAG, "Pre-gen waiting for mutex: ${text.take(40)}...")
                    // Wait briefly then retry — speak() hold is short
                    generateMutex.withLock {
                        // Got the lock now, generate
                        generateOne(text, cacheKey, speakerId, speed)
                    }
                } else {
                    try {
                        generateOne(text, cacheKey, speakerId, speed)
                    } finally {
                        generateMutex.unlock()
                    }
                }
            }
        }
    }

    private suspend fun generateOne(
        text: String,
        cacheKey: String,
        speakerId: Int,
        speed: Float
    ) {
        try {
            Logger.d(TAG, "Pre-generating: ${text.take(40)}...")
            val audio = ttsWrapper?.generate(text, speakerId, speed)
            if (audio != null && audio.samples.isNotEmpty()) {
                audioCache[cacheKey] = audio
                Logger.d(TAG, "Cached audio (${audioCache.size}/$maxCacheSize): ${text.take(40)}...")
                // Evict oldest if cache too large
                while (audioCache.size > maxCacheSize) {
                    val oldest = audioCache.keys.firstOrNull() ?: break
                    audioCache.remove(oldest)
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Pre-generation failed (non-fatal): ${e.message}")
        }
    }

    /** How many sentences to look ahead — exposed for TTSManager */
    fun getLookAheadCount(): Int = lookAheadCount

    /**
     * Clear the pre-generation cache (called on stop/model change).
     */
    fun clearCache() {
        pregenerateJob?.cancel()
        pregenerateJob = null
        audioCache.clear()
    }

    private fun buildCacheKey(text: String, speakerId: Int, speed: Float): String {
        return "${speakerId}_${speed}_${text.hashCode()}"
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        val models = getDownloadedModels()
        if (models.isEmpty()) {
            Logger.w(TAG, "No models downloaded yet")
            onError("No TTS models downloaded. Please download a model first.")
            return
        }

        val modelToLoad = currentModelId?.let { id ->
            models.find { it.id == id }
        } ?: models.first()

        loadModel(modelToLoad.id, onReady, onError)
    }

    /**
     * Load a specific model. Can be called to switch models at runtime.
     */
    fun loadModel(modelId: String, onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val modelDir = File(modelsDir, modelId)
        if (!modelDir.exists()) {
            onError("Model directory not found: ${modelDir.absolutePath}")
            return
        }

        clearCache()

        try {
            val modelConfig = detectModelConfig(modelDir)
            if (modelConfig == null) {
                onError("Could not detect model type in: ${modelDir.name}")
                return
            }

            ttsWrapper = SherpaOnnxWrapper.create(modelConfig)
            if (ttsWrapper == null) {
                onError("Failed to create Sherpa-ONNX TTS instance for: ${modelDir.name}")
                return
            }

            sampleRate = ttsWrapper!!.sampleRate
            currentModelId = modelId
            isReady = true
            Logger.d(TAG, "Loaded model: $modelId (sampleRate=$sampleRate)")
            onReady()

        } catch (e: Exception) {
            Logger.e(TAG, "Error loading model $modelId", e)
            isReady = false
            onError("Error loading model: ${e.message}")
        }
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
        if (!isReady || ttsWrapper == null) {
            onError("SherpaOnnxEngine not initialized")
            return
        }

        // Stop any in-progress playback (but keep the cache for look-ahead)
        stopPlayback()

        isStopped = false

        synthesisJob = scope.launch {
            try {
                val cacheKey = buildCacheKey(text, currentSpeakerId, speed)
                val cached = audioCache.remove(cacheKey)

                val audio = if (cached != null) {
                    Logger.d(TAG, "Using pre-generated audio for: ${text.take(50)}...")
                    cached
                } else {
                    Logger.d(TAG, "Generating audio for: ${text.take(50)}...")
                    // Wait for mutex — pregenerate will yield since it uses tryLock
                    generateMutex.withLock {
                        ttsWrapper!!.generate(
                            text = text,
                            speakerId = currentSpeakerId,
                            speed = speed
                        )
                    }
                }

                if (isStopped || !isActive) return@launch
                if (audio == null || audio.samples.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("Empty audio generated") }
                    return@launch
                }

                withContext(Dispatchers.Main) { onStart() }
                playAudio(audio.samples, audio.sampleRate, volume)

                // Check BOTH isStopped AND isActive to prevent race condition:
                // When a new speak() call resets isStopped to false for the new job,
                // the OLD cancelled job must not fire onDone. isActive stays false
                // after cancel() even if isStopped was reset.
                if (!isStopped && isActive) {
                    withContext(Dispatchers.Main) { onDone() }
                }

            } catch (e: CancellationException) {
                // Normal cancellation (e.g. new sentence starting) — rethrow, not an error
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Synthesis/playback error", e)
                if (!isStopped && isActive) {
                    withContext(Dispatchers.Main) { onError("TTS error: ${e.message}") }
                }
            }
        }
    }

    /**
     * Internal: stop current playback without clearing cache.
     * Used between sentences to allow look-ahead to work.
     */
    private fun stopPlayback() {
        isStopped = true
        synthesisJob?.cancel()
        synthesisJob = null
        releaseAudioTrack()
    }

    /**
     * Full stop: user-initiated, clears everything including cache.
     */
    override fun stop() {
        stopPlayback()
        clearCache()
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.stop()
                track.release()
            } catch (e: Exception) {
                Logger.e(TAG, "Error stopping AudioTrack", e)
            }
        }
        audioTrack = null
    }

    override fun shutdown() {
        stop()
        scope.cancel()
        ttsWrapper?.release()
        ttsWrapper = null
        isReady = false
    }

    // ── Voice / model management ─────────────────────────────────

    override fun getAvailableVoices(): List<VoiceInfo> {
        return getDownloadedModels()
    }

    override fun setVoice(voiceId: String) {
        if (voiceId != currentModelId) {
            loadModel(voiceId)
        }
    }

    override fun getCurrentVoiceId(): String? = currentModelId

    fun setSpeakerId(id: Int) {
        currentSpeakerId = id
    }

    fun getCurrentSpeakerIdValue(): Int = currentSpeakerId

    /**
     * Expose the wrapper for direct generation (used by AudioExporter).
     */
    fun getTtsWrapper(): SherpaOnnxWrapper? = ttsWrapper

    // ── Downloaded models discovery ──────────────────────────────

    private fun getDownloadedModels(): List<VoiceInfo> {
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()
            ?.filter { it.isDirectory && hasModelFiles(it) }
            ?.map { dir ->
                VoiceInfo(
                    id = dir.name,
                    displayName = formatModelName(dir.name),
                    isDownloaded = true,
                    engineId = ENGINE_ID
                )
            }
            ?.sortedBy { it.displayName }
            ?: emptyList()
    }

    private fun hasModelFiles(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        val hasOnnx = files.any { it.isFile && it.extension == "onnx" }
        val hasTokens = files.any { it.isFile && it.name == "tokens.txt" }
        if (hasOnnx && hasTokens) return true

        // Some archives extract with a nested subdirectory — check one level deeper
        for (sub in files.filter { it.isDirectory }) {
            val subFiles = sub.listFiles() ?: continue
            val subOnnx = subFiles.any { it.isFile && it.extension == "onnx" }
            val subTokens = subFiles.any { it.isFile && it.name == "tokens.txt" }
            if (subOnnx && subTokens) return true
        }
        return false
    }

    private fun formatModelName(dirName: String): String {
        return when {
            dirName.contains("piper", true) -> {
                val parts = dirName.replace("vits-piper-", "").split("-")
                val locale = parts.getOrNull(0)?.replace("_", "-") ?: ""
                val name = parts.getOrNull(1)?.replaceFirstChar { it.uppercase() } ?: dirName
                val quality = parts.getOrNull(2)?.replaceFirstChar { it.uppercase() } ?: ""
                "Piper: $name ($quality, $locale)"
            }
            dirName.contains("kitten", true) -> {
                "KittenTTS: ${dirName.substringAfter("kitten-").replaceFirstChar { it.uppercase() }}"
            }
            dirName.contains("kokoro", true) -> {
                "Kokoro: ${dirName.substringAfter("kokoro-").replaceFirstChar { it.uppercase() }}"
            }
            else -> dirName.replaceFirstChar { it.uppercase() }
        }
    }

    // ── Model config detection ───────────────────────────────────

    /**
     * Find the directory that actually contains model files.
     * Handles cases where archive extraction created a nested subdirectory.
     */
    private fun resolveModelRoot(modelDir: File): File {
        val files = modelDir.listFiles() ?: return modelDir
        val hasOnnx = files.any { it.isFile && it.extension == "onnx" }
        val hasTokens = files.any { it.isFile && it.name == "tokens.txt" }
        if (hasOnnx && hasTokens) return modelDir

        // Check one level deeper for single-subdirectory nesting
        for (sub in files.filter { it.isDirectory }) {
            val subFiles = sub.listFiles() ?: continue
            if (subFiles.any { it.isFile && it.extension == "onnx" } &&
                subFiles.any { it.isFile && it.name == "tokens.txt" }) {
                Logger.d(TAG, "Model files found in nested dir: ${sub.name}")
                return sub
            }
        }
        return modelDir
    }

    private fun detectModelConfig(modelDir: File): SherpaModelConfig? {
        val resolvedDir = resolveModelRoot(modelDir)
        val files = resolvedDir.listFiles() ?: return null
        val onnxFiles = files.filter { it.extension == "onnx" }
        val tokensFile = files.find { it.name == "tokens.txt" }
        val dataDir = files.find { it.isDirectory && it.name.contains("espeak") }
        val lexiconFile = files.find { it.name == "lexicon.txt" }
        val vocoderFile = files.find { it.name.contains("vocoder") || it.name.contains("vocos") }
        val voicesFile = files.find { it.name == "voices.bin" }

        Logger.d(TAG, "Model ${modelDir.name}: onnx=${onnxFiles.map{it.name}}, " +
                "tokens=${tokensFile?.name}, voices=${voicesFile?.name}, " +
                "dataDir=${dataDir?.name}, resolved=${resolvedDir.absolutePath}")

        if (onnxFiles.isEmpty() || tokensFile == null) {
            Logger.e(TAG, "Missing required files in ${modelDir.name}")
            return null
        }

        val dirName = modelDir.name.lowercase()

        return when {
            dirName.contains("kokoro") -> {
                val modelFile = onnxFiles.find { it.name.contains("model") } ?: onnxFiles.first()
                SherpaModelConfig(
                    type = ModelType.KOKORO,
                    modelPath = modelFile.absolutePath,
                    tokensPath = tokensFile.absolutePath,
                    lexiconPath = lexiconFile?.absolutePath,
                    dataDir = dataDir?.absolutePath,
                    vocoderPath = vocoderFile?.absolutePath,
                    voicesPath = voicesFile?.absolutePath,
                    modelDir = resolvedDir.absolutePath
                )
            }
            dirName.contains("kitten") -> {
                val modelFile = onnxFiles.find { it.name.contains("model") } ?: onnxFiles.first()
                SherpaModelConfig(
                    type = ModelType.KITTEN,
                    modelPath = modelFile.absolutePath,
                    tokensPath = tokensFile.absolutePath,
                    lexiconPath = lexiconFile?.absolutePath,
                    dataDir = dataDir?.absolutePath,
                    voicesPath = voicesFile?.absolutePath,
                    modelDir = resolvedDir.absolutePath
                )
            }
            dirName.contains("matcha") -> {
                val acousticModel = onnxFiles.find { it.name.contains("model") } ?: onnxFiles.first()
                SherpaModelConfig(
                    type = ModelType.MATCHA,
                    modelPath = acousticModel.absolutePath,
                    tokensPath = tokensFile.absolutePath,
                    lexiconPath = lexiconFile?.absolutePath,
                    dataDir = dataDir?.absolutePath,
                    vocoderPath = vocoderFile?.absolutePath,
                    modelDir = resolvedDir.absolutePath
                )
            }
            else -> {
                val modelFile = onnxFiles.find {
                    it.name.contains("model") || it.name.endsWith(".onnx")
                } ?: onnxFiles.first()
                SherpaModelConfig(
                    type = ModelType.VITS,
                    modelPath = modelFile.absolutePath,
                    tokensPath = tokensFile.absolutePath,
                    lexiconPath = lexiconFile?.absolutePath,
                    dataDir = dataDir?.absolutePath,
                    modelDir = resolvedDir.absolutePath
                )
            }
        }
    }

    // ── Audio playback ───────────────────────────────────────────

    private suspend fun playAudio(samples: FloatArray, rate: Int, volume: Float) {
        if (isStopped || !currentCoroutineContext()[Job]!!.isActive) return

        // Release any lingering previous track
        releaseAudioTrack()

        // Validate sample rate
        if (rate <= 0) {
            Logger.e(TAG, "Invalid sample rate: $rate, using engine default: $sampleRate")
        }
        val playbackRate = if (rate > 0) rate else sampleRate
        if (playbackRate <= 0) {
            Logger.e(TAG, "No valid sample rate available, cannot play audio")
            return
        }

        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            pcm[i] = (clamped * 32767).toInt().toShort()
        }

        Logger.d(TAG, "Playing audio: ${samples.size} samples, rate=$playbackRate, vol=$volume")

        val bufferSize = AudioTrack.getMinBufferSize(
            playbackRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize <= 0) {
            Logger.e(TAG, "Invalid buffer size: $bufferSize for rate=$playbackRate")
            return
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.let { track ->
            track.setVolume(volume)
            track.play()

            // Write in chunks for MODE_STREAM
            val chunkSize = bufferSize / 2  // write in half-buffer chunks
            var offset = 0
            while (offset < pcm.size && !isStopped) {
                val remaining = pcm.size - offset
                val toWrite = minOf(chunkSize, remaining)
                val written = track.write(pcm, offset, toWrite)
                if (written < 0) {
                    Logger.e(TAG, "AudioTrack write error: $written")
                    break
                }
                offset += written
            }

            // Wait for playback to finish draining using cancellable delay
            if (!isStopped) {
                val durationMs = (samples.size.toLong() * 1000) / playbackRate
                val startTime = System.currentTimeMillis()

                while (!isStopped &&
                    (System.currentTimeMillis() - startTime) < durationMs + 200
                ) {
                    delay(50)  // cancellable — cooperates with job.cancel()
                }
            }

            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Logger.e(TAG, "Error releasing AudioTrack", e)
            }
        }
        audioTrack = null
    }
}

// ── Config types ─────────────────────────────────────────────────

enum class ModelType {
    VITS,       // Piper and other VITS-based models
    MATCHA,     // Matcha-TTS (needs separate vocoder)
    KOKORO,     // Kokoro models
    KITTEN      // KittenTTS models
}

data class SherpaModelConfig(
    val type: ModelType,
    val modelPath: String,
    val tokensPath: String,
    val lexiconPath: String? = null,
    val dataDir: String? = null,
    val vocoderPath: String? = null,
    val voicesPath: String? = null,
    val modelDir: String = ""
)

// ── Sherpa-ONNX Direct Wrapper (no reflection) ───────────────────

/**
 * Wraps the Sherpa-ONNX OfflineTts Kotlin API using direct imports.
 *
 * Requires:
 *  - Sherpa-ONNX native .so libs in jniLibs/
 *  - Sherpa-ONNX Kotlin API files in com.k2fsa.sherpa.onnx package
 *
 * No reflection needed — all calls are compile-time checked.
 */
class SherpaOnnxWrapper private constructor(
    private val tts: OfflineTts,
    val sampleRate: Int
) {
    companion object {
        private const val TAG = "SherpaOnnxWrapper"

        /**
         * Create a wrapper by building the appropriate OfflineTtsConfig
         * and instantiating OfflineTts.
         *
         * Returns null if the native library fails to load or config is invalid.
         */
        fun create(config: SherpaModelConfig): SherpaOnnxWrapper? {
            return try {
                val ttsConfig = buildConfig(config)
                val tts = OfflineTts(config = ttsConfig)

                // sampleRate() is also a JNI call — protect it
                val sr = try {
                    tts.sampleRate()
                } catch (e: Exception) {
                    Logger.w(TAG, "sampleRate() failed, trying reflection")
                    try {
                        val ptrField = tts.javaClass.getDeclaredField("ptr")
                        ptrField.isAccessible = true
                        val ptr = ptrField.getLong(tts)
                        val method = tts.javaClass.getDeclaredMethod(
                            "sampleRateImpl", Long::class.javaPrimitiveType
                        )
                        method.isAccessible = true
                        method.invoke(tts, ptr) as Int
                    } catch (e2: Exception) {
                        Logger.w(TAG, "sampleRate reflection failed, defaulting to 22050")
                        22050
                    }
                }

                Logger.d(TAG, "Created OfflineTts: sampleRate=$sr, type=${config.type}")
                SherpaOnnxWrapper(tts, sr)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create Sherpa-ONNX TTS", e)
                null
            }
        }

        /**
         * Determine optimal thread count based on device CPU cores.
         * For heavy models (Kokoro, Kitten, Matcha): use up to half the cores (min 2, max 4).
         * For light models (VITS/Piper): always 2.
         * This avoids ANR on emulators/low-core devices while using more cores on flagships.
         */
        private fun getOptimalThreadCount(isHeavyModel: Boolean): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (isHeavyModel) {
                (cores / 2).coerceIn(2, 4)
            } else {
                2
            }
        }

        private fun buildConfig(config: SherpaModelConfig): OfflineTtsConfig {
            val lightThreads = getOptimalThreadCount(isHeavyModel = false)
            val heavyThreads = getOptimalThreadCount(isHeavyModel = true)
            val cores = Runtime.getRuntime().availableProcessors()
            Logger.d(TAG, "CPU cores=$cores, threads: light=$lightThreads, heavy=$heavyThreads, model=${config.type}")

            val modelConfig = when (config.type) {
                ModelType.VITS -> {
                    val vits = OfflineTtsVitsModelConfig(
                        model = config.modelPath,
                        lexicon = config.lexiconPath ?: "",
                        tokens = config.tokensPath,
                        dataDir = config.dataDir ?: "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    )
                    OfflineTtsModelConfig(
                        vits = vits,
                        numThreads = lightThreads,
                        debug = false,
                        provider = "cpu"
                    )
                }

                ModelType.MATCHA -> {
                    // Matcha uses the same VITS config path in older sherpa-onnx versions.
                    // If OfflineTtsMatchaModelConfig exists in your API files, use it instead.
                    // Otherwise fall back to VITS config which works for most matcha models.
                    try {
                        val matcha = com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(
                            acousticModel = config.modelPath,
                            vocoder = config.vocoderPath ?: "",
                            lexicon = config.lexiconPath ?: "",
                            tokens = config.tokensPath,
                            dataDir = config.dataDir ?: ""
                        )
                        OfflineTtsModelConfig(
                            matcha = matcha,
                            numThreads = heavyThreads,
                            debug = false,
                            provider = "cpu"
                        )
                    } catch (e: NoClassDefFoundError) {
                        Logger.w(TAG, "OfflineTtsMatchaModelConfig not available, using VITS fallback")
                        buildVitsFallback(config)
                    }
                }

                ModelType.KOKORO -> {
                    try {
                        val kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
                            model = config.modelPath,
                            voices = config.voicesPath ?: "",
                            tokens = config.tokensPath,
                            dataDir = config.dataDir ?: "",
                            lengthScale = 1.0f
                        )
                        OfflineTtsModelConfig(
                            kokoro = kokoro,
                            numThreads = heavyThreads,
                            debug = false,
                            provider = "cpu"
                        )
                    } catch (e: NoClassDefFoundError) {
                        Logger.w(TAG, "OfflineTtsKokoroModelConfig not available, using VITS fallback")
                        buildVitsFallback(config)
                    }
                }

                ModelType.KITTEN -> {
                    try {
                        val kitten = com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig(
                            model = config.modelPath,
                            voices = config.voicesPath ?: "",
                            tokens = config.tokensPath,
                            dataDir = config.dataDir ?: ""
                        )
                        OfflineTtsModelConfig(
                            kitten = kitten,
                            numThreads = heavyThreads,
                            debug = false,
                            provider = "cpu"
                        )
                    } catch (e: NoClassDefFoundError) {
                        Logger.w(TAG, "OfflineTtsKittenModelConfig not available, using VITS fallback")
                        buildVitsFallback(config)
                    }
                }
            }

            return OfflineTtsConfig(
                model = modelConfig,
                maxNumSentences = 1   // process one sentence at a time for streaming feel
            )
        }

        /**
         * Fallback: build a VITS config when the model-specific config class
         * isn't available in the current sherpa-onnx version.
         */
        private fun buildVitsFallback(config: SherpaModelConfig): OfflineTtsModelConfig {
            val vits = OfflineTtsVitsModelConfig(
                model = config.modelPath,
                lexicon = config.lexiconPath ?: "",
                tokens = config.tokensPath,
                dataDir = config.dataDir ?: "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            return OfflineTtsModelConfig(
                vits = vits,
                numThreads = getOptimalThreadCount(isHeavyModel = false),
                debug = false,
                provider = "cpu"
            )
        }
    }

    /**
     * Generate audio samples for the given text.
     * Returns GeneratedAudioData or null on failure.
     *
     * Handles two native library return formats:
     *  - New API: generateImpl returns GeneratedAudio object (has .samples, .sampleRate)
     *  - Old API: generateImpl returns Object[] = [FloatArray, Int]
     *
     * We use reflection to call generateImpl directly so we can handle both.
     */
    fun generate(text: String, speakerId: Int = 0, speed: Float = 1.0f): GeneratedAudioData? {
        return try {
            // First try the normal API — works when .so and Kotlin API are the same version
            try {
                val audio = tts.generate(text = text, sid = speakerId, speed = speed)
                if (audio.samples.isEmpty()) {
                    Logger.w(TAG, "Generated empty audio for: ${text.take(30)}")
                    return null
                }
                return GeneratedAudioData(
                    samples = audio.samples,
                    sampleRate = audio.sampleRate
                )
            } catch (e: Exception) {
                // JNI return type mismatch — fall through to reflection path
                Logger.w(TAG, "Direct generate() failed (${e.javaClass.simpleName}), trying reflection fallback")
            }

            // Reflection fallback: call generateImpl ourselves and parse Object[] result
            val ptrField = tts.javaClass.getDeclaredField("ptr")
            ptrField.isAccessible = true
            val ptr = ptrField.getLong(tts)

            val generateImplMethod = tts.javaClass.getDeclaredMethod(
                "generateImpl",
                Long::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            generateImplMethod.isAccessible = true

            val result = generateImplMethod.invoke(tts, ptr, text, speakerId, speed)

            when (result) {
                is Array<*> -> {
                    // Old API: Object[] = [FloatArray, Integer]
                    val samples = result[0] as? FloatArray
                    val sr = (result[1] as? Number)?.toInt() ?: sampleRate

                    if (samples == null || samples.isEmpty()) {
                        Logger.w(TAG, "Reflection: empty samples from Object[] result")
                        null
                    } else {
                        Logger.d(TAG, "Reflection: got ${samples.size} samples at ${sr}Hz")
                        GeneratedAudioData(samples = samples, sampleRate = sr)
                    }
                }
                else -> {
                    // New API: GeneratedAudio object — extract fields via reflection
                    if (result == null) {
                        Logger.w(TAG, "Reflection: null result from generateImpl")
                        return null
                    }
                    val samplesField = result.javaClass.getDeclaredField("samples")
                    samplesField.isAccessible = true
                    val samples = samplesField.get(result) as? FloatArray

                    val srField = result.javaClass.getDeclaredField("sampleRate")
                    srField.isAccessible = true
                    val sr = srField.getInt(result)

                    if (samples == null || samples.isEmpty()) {
                        Logger.w(TAG, "Reflection: empty samples from GeneratedAudio")
                        null
                    } else {
                        GeneratedAudioData(samples = samples, sampleRate = sr)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error generating audio (all paths failed)", e)
            null
        }
    }

    /**
     * Release native resources. Wrapper cannot be used after this.
     */
    fun release() {
        try {
            tts.release()
            Logger.d(TAG, "Released OfflineTts")
        } catch (e: Exception) {
            // Try reflection fallback for release
            try {
                val ptrField = tts.javaClass.getDeclaredField("ptr")
                ptrField.isAccessible = true
                val ptr = ptrField.getLong(tts)
                if (ptr != 0L) {
                    val method = tts.javaClass.getDeclaredMethod(
                        "releaseImpl", Long::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    method.invoke(tts, ptr)
                    ptrField.setLong(tts, 0L)
                }
                Logger.d(TAG, "Released OfflineTts via reflection")
            } catch (e2: Exception) {
                Logger.e(TAG, "Error releasing native TTS", e2)
            }
        }
    }
}

data class GeneratedAudioData(
    val samples: FloatArray,
    val sampleRate: Int
)