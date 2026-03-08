package com.abhinavxt.novelreader.data.tts

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exports chapter text as a WAV audio file using either:
 *  - Sherpa-ONNX (on-device neural TTS) — generates raw PCM samples
 *  - Google TTS — uses synthesizeToFile() to produce per-sentence WAVs
 *
 * Flow:
 *   1. Split chapter text into sentences
 *   2. Generate audio per sentence (engine-dependent)
 *   3. Stream PCM to a temp file (constant memory)
 *   4. Write final WAV to Music/NovelReader/ via MediaStore (Android 10+)
 *      or direct file I/O (Android 9 and below)
 *
 * Exposes [exportState] as a StateFlow for UI observation.
 */
class AudioExporter(
    private val context: Context,
    private val ttsManager: com.abhinavxt.novelreader.data.TTSManager
) {
    companion object {
        private const val TAG = "AudioExporter"
        private const val SUBFOLDER = "NovelReader"
        private const val SILENCE_BETWEEN_SENTENCES_MS = 300
        private const val SILENCE_BETWEEN_PARAGRAPHS_MS = 600
    }

    // ── State ────────────────────────────────────────────────────

    sealed interface ExportState {
        object Idle : ExportState
        data class Exporting(
            val chapterId: String,
            val progress: Float,        // 0.0 to 1.0
            val currentSentence: Int,
            val totalSentences: Int
        ) : ExportState
        data class Completed(val chapterId: String, val filePath: String) : ExportState
        data class Error(val chapterId: String, val message: String) : ExportState
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    // Track which chapters are currently exporting
    private val _exportingChapters = MutableStateFlow<Set<String>>(emptySet())
    val exportingChapters: StateFlow<Set<String>> = _exportingChapters.asStateFlow()

    // ── Public API ───────────────────────────────────────────────

    /**
     * Check if a chapter's audio has already been exported for a specific voice.
     * If voiceName is null, checks if ANY voice export exists.
     */
    fun isChapterExported(novelTitle: String, chapterTitle: String, voiceName: String? = null): Boolean {
        val novelFolder = sanitizeFilename(novelTitle)
        val chapterPrefix = sanitizeFilename(chapterTitle)
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val novelDir = File(musicDir, "$SUBFOLDER/$novelFolder")
        if (!novelDir.exists()) return false

        return if (voiceName != null) {
            // Check for exact voice match: "Chapter_1_Amy_Low.wav"
            val voiceSuffix = sanitizeFilename(voiceName)
            val expectedName = "${chapterPrefix}_${voiceSuffix}"
            novelDir.listFiles()?.any {
                it.isFile && it.extension == "wav" && it.nameWithoutExtension == expectedName
            } == true
        } else {
            // Match any voice: files starting with chapterPrefix
            novelDir.listFiles()?.any {
                it.isFile && it.extension == "wav" && it.nameWithoutExtension.startsWith(chapterPrefix)
            } == true
        }
    }

    /**
     * Get set of chapter IDs that have exported audio for a novel with the specified voice.
     * If voiceName is null, returns chapters exported with ANY voice.
     *
     * Lists the directory ONCE and matches all chapters against the cached file list
     * to avoid O(chapters × listFiles) disk reads.
     */
    fun getExportedChapterIds(
        novelTitle: String,
        chapters: List<Pair<String, String>>,  // List of (chapterId, chapterTitle)
        voiceName: String? = null
    ): Set<String> {
        val novelFolder = sanitizeFilename(novelTitle)
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val novelDir = File(musicDir, "$SUBFOLDER/$novelFolder")
        if (!novelDir.exists()) return emptySet()

        // List files ONCE — this is the expensive I/O call
        val wavFileNames = novelDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: return emptySet()

        val voiceSuffix = voiceName?.let { sanitizeFilename(it) }

        return chapters.filter { (_, title) ->
            val chapterPrefix = sanitizeFilename(title)
            if (voiceSuffix != null) {
                // Exact match: "Chapter_1_Amy_Low"
                val expectedName = "${chapterPrefix}_${voiceSuffix}"
                expectedName in wavFileNames
            } else {
                // Any voice: any file starting with chapterPrefix
                wavFileNames.any { it.startsWith(chapterPrefix) }
            }
        }.map { (id, _) -> id }.toSet()
    }

    /**
     * Export a chapter's text as a WAV audio file.
     *
     * @param chapterId    Unique chapter identifier (for state tracking)
     * @param novelTitle   Used in the output filename
     * @param chapterTitle Used in the output filename
     * @param text         The full chapter text to convert
     * @param speakerId    Sherpa speaker ID (default 0)
     * @param speed        Speech speed multiplier (default 1.0)
     */
    suspend fun exportChapter(
        chapterId: String,
        novelTitle: String,
        chapterTitle: String,
        voiceName: String,
        text: String,
        speakerId: Int = 0,
        speed: Float = 1.0f
    ): Boolean = withContext(Dispatchers.Default) {

        val sherpaEngine = ttsManager.sherpaEngine
        val googleEngine = ttsManager.googleEngine
        val activeEngineId = ttsManager.activeEngineId.value

        // Determine which engine to use
        val useSherpa = activeEngineId == SherpaOnnxEngine.ENGINE_ID
                && sherpaEngine.isReady
                && sherpaEngine.getTtsWrapper() != null

        val useGoogle = activeEngineId == GoogleTTSEngine.ENGINE_ID
                && googleEngine.isReady

        if (!useSherpa && !useGoogle) {
            _exportState.value = ExportState.Error(chapterId,
                "No TTS engine ready. Please select a voice first.")
            return@withContext false
        }

        _exportingChapters.value = _exportingChapters.value + chapterId

        try {
            // 1. Split text into sentences
            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) {
                _exportState.value = ExportState.Error(chapterId, "No text to export")
                return@withContext false
            }

            Logger.d(TAG, "Exporting ${sentences.size} sentences for: $chapterTitle (engine=${if (useSherpa) "Sherpa" else "Google"})")

            // 2. Stream PCM to a temp file (avoids holding entire chapter in memory)
            val tempFile = File(context.cacheDir, "tts_export_${System.currentTimeMillis()}.pcm")
            tempFile.createNewFile()
            var totalPcmBytes = 0L
            var sampleRate = if (useSherpa) sherpaEngine.getTtsWrapper()!!.sampleRate else 22050

            try {
                FileOutputStream(tempFile).use { pcmOut ->

                    // Silence buffer — computed using initial sampleRate, all zeros
                    val sentenceSilenceSamples = (sampleRate * SILENCE_BETWEEN_SENTENCES_MS) / 1000
                    val paragraphSilenceSamples = (sampleRate * SILENCE_BETWEEN_PARAGRAPHS_MS) / 1000
                    val silenceBuffer = ByteArray(paragraphSilenceSamples * 2)

                    for ((index, entry) in sentences.withIndex()) {
                        ensureActive()

                        _exportState.value = ExportState.Exporting(
                            chapterId = chapterId,
                            progress = index.toFloat() / sentences.size,
                            currentSentence = index + 1,
                            totalSentences = sentences.size
                        )

                        if (useSherpa) {
                            // ── Sherpa path: generate raw samples ──
                            val audio = sherpaEngine.getTtsWrapper()!!.generate(
                                text = entry.text,
                                speakerId = speakerId,
                                speed = speed
                            )

                            if (audio != null && audio.samples.isNotEmpty()) {
                                writeSamplesToPcm(pcmOut, audio.samples)
                                totalPcmBytes += audio.samples.size * 2L
                            }
                        } else {
                            // ── Google TTS path: synthesize to temp WAV, extract PCM ──
                            // synthesizeToFile must be called from Main thread (Looper needed)
                            val sentenceFile = File(context.cacheDir, "tts_sentence_$index.wav")
                            try {
                                val success = withContext(Dispatchers.Main) {
                                    googleEngine.synthesizeToFile(
                                        text = entry.text,
                                        outputFile = sentenceFile,
                                        speed = speed
                                    )
                                }

                                if (success && sentenceFile.exists() && sentenceFile.length() > 44) {
                                    // Read sample rate from WAV header on first sentence
                                    if (index == 0) {
                                        sampleRate = readWavSampleRate(sentenceFile)
                                    }
                                    // Copy raw PCM data (skip 44-byte WAV header) to pcmOut
                                    val pcmBytes = copyPcmFromWav(sentenceFile, pcmOut)
                                    if (pcmBytes > 0) {
                                        totalPcmBytes += pcmBytes
                                    }
                                } else {
                                    Logger.w(TAG, "Google TTS sentence $index failed (success=$success, exists=${sentenceFile.exists()})")
                                }
                            } finally {
                                sentenceFile.delete()
                            }
                        }

                        // Write silence gap
                        val silenceSamples = if (entry.isParagraphEnd)
                            paragraphSilenceSamples else sentenceSilenceSamples
                        val silenceBytes = silenceSamples * 2
                        pcmOut.write(silenceBuffer, 0, silenceBytes)
                        totalPcmBytes += silenceBytes
                    }

                    // Flush to ensure all data is on disk
                    pcmOut.flush()
                    pcmOut.fd.sync()
                }

                ensureActive()

                if (totalPcmBytes == 0L) {
                    _exportState.value = ExportState.Error(chapterId, "Generated empty audio")
                    return@withContext false
                }

                // 3. Write final WAV file (header + PCM data from temp)
                _exportState.value = ExportState.Exporting(
                    chapterId = chapterId,
                    progress = 0.95f,
                    currentSentence = sentences.size,
                    totalSentences = sentences.size
                )

                Logger.d(TAG, "Temp PCM file: ${tempFile.absolutePath}, size=${tempFile.length()}, expected=$totalPcmBytes")

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    _exportState.value = ExportState.Error(chapterId, "Temp audio file missing or empty")
                    return@withContext false
                }

                val novelFolder = sanitizeFilename(novelTitle)
                val voiceSuffix = sanitizeFilename(voiceName)
                val chapterFile = sanitizeFilename(chapterTitle) + "_" + voiceSuffix
                val filePath = writeWavFromTempFile(novelFolder, chapterFile, tempFile, totalPcmBytes, sampleRate)

                if (filePath != null) {
                    Logger.d(TAG, "Export complete: $filePath (${totalPcmBytes / 1024}KB PCM)")
                    _exportState.value = ExportState.Completed(chapterId, filePath)
                    return@withContext true
                } else {
                    _exportState.value = ExportState.Error(chapterId, "Failed to save audio file")
                    return@withContext false
                }

            } finally {
                tempFile.delete()
            }

        } catch (e: CancellationException) {
            Logger.d(TAG, "Export cancelled for: $chapterTitle")
            _exportState.value = ExportState.Idle
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Export failed for: $chapterTitle", e)
            _exportState.value = ExportState.Error(chapterId, "Export failed: ${e.message}")
            return@withContext false
        } finally {
            _exportingChapters.value = _exportingChapters.value - chapterId
        }
    }

    /**
     * Cancel any in-progress export.
     */
    fun cancel() {
        _exportState.value = ExportState.Idle
        _exportingChapters.value = emptySet()
    }

    /**
     * Reset state back to idle (e.g. after dismissing a completion/error message).
     */
    fun resetState() {
        _exportState.value = ExportState.Idle
    }

    // ── Text splitting ───────────────────────────────────────────

    private data class SentenceEntry(
        val text: String,
        val isParagraphEnd: Boolean
    )

    private fun splitIntoSentences(text: String): List<SentenceEntry> {
        val result = mutableListOf<SentenceEntry>()
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        for (paragraph in paragraphs) {
            // Split on sentence-ending punctuation followed by a space or end-of-string
            val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for ((i, sentence) in sentences.withIndex()) {
                result.add(SentenceEntry(
                    text = sentence,
                    isParagraphEnd = (i == sentences.lastIndex)
                ))
            }
        }
        return result
    }

    // ── Streaming PCM writing ──────────────────────────────────────

    /**
     * Convert float samples to 16-bit PCM and write to stream in chunks.
     * Uses a simple byte array to avoid ByteBuffer quirks.
     */
    private fun writeSamplesToPcm(
        out: FileOutputStream,
        samples: FloatArray
    ) {
        val buffer = ByteArray(8192)
        var bufPos = 0

        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcmVal = (clamped * 32767).toInt().toShort()
            // Little-endian: low byte first
            buffer[bufPos++] = (pcmVal.toInt() and 0xFF).toByte()
            buffer[bufPos++] = ((pcmVal.toInt() shr 8) and 0xFF).toByte()

            if (bufPos >= buffer.size) {
                out.write(buffer, 0, bufPos)
                bufPos = 0
            }
        }
        // Flush remaining
        if (bufPos > 0) {
            out.write(buffer, 0, bufPos)
        }
    }

    /**
     * Write final WAV: header + copy PCM from temp file.
     * Streams the temp file in 64KB chunks so memory stays flat.
     */
    private suspend fun writeWavFromTempFile(
        novelFolder: String,
        chapterFile: String,
        tempFile: File,
        pcmSize: Long,
        sampleRate: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            if (!tempFile.exists()) {
                Logger.e(TAG, "Temp file does not exist: ${tempFile.absolutePath}")
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeWavViaMediaStore(novelFolder, chapterFile, tempFile, pcmSize, sampleRate)
            } else {
                writeWavDirectly(novelFolder, chapterFile, tempFile, pcmSize, sampleRate)
            }
        }
    }

    private fun writeWavViaMediaStore(
        novelFolder: String,
        chapterFile: String,
        tempFile: File,
        pcmSize: Long,
        sampleRate: Int
    ): String? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$chapterFile.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER/$novelFolder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                writeWavHeader(out, pcmSize.toInt(), sampleRate)
                copyFileToStream(tempFile, out)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER/$novelFolder/$chapterFile.wav"
        } catch (e: Exception) {
            Logger.e(TAG, "MediaStore write failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun writeWavDirectly(
        novelFolder: String,
        chapterFile: String,
        tempFile: File,
        pcmSize: Long,
        sampleRate: Int
    ): String? {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val outputDir = File(musicDir, "$SUBFOLDER/$novelFolder")
        outputDir.mkdirs()

        val outputFile = File(outputDir, "$chapterFile.wav")

        return try {
            FileOutputStream(outputFile).use { out ->
                writeWavHeader(out, pcmSize.toInt(), sampleRate)
                copyFileToStream(tempFile, out)
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf("audio/wav"),
                null
            )

            outputFile.absolutePath
        } catch (e: Exception) {
            Logger.e(TAG, "Direct write failed", e)
            null
        }
    }

    /**
     * Copy file to output stream in 64KB chunks.
     */
    private fun copyFileToStream(file: File, out: OutputStream) {
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
            }
        }
    }

    /**
     * Write a standard 44-byte WAV header for mono 16-bit PCM.
     */
    private fun writeWavHeader(
        outputStream: OutputStream,
        dataSize: Int,
        sampleRate: Int
    ) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)  // file size - 8
        header.put("WAVE".toByteArray())

        // fmt subchunk
        header.put("fmt ".toByteArray())
        header.putInt(16)              // subchunk1 size (PCM)
        header.putShort(1)             // audio format (1 = PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        // data subchunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        outputStream.write(header.array())
    }

    // ── Google TTS WAV helpers ─────────────────────────────────────

    /**
     * Read sample rate from a WAV file header (bytes 24-27).
     */
    private fun readWavSampleRate(wavFile: File): Int {
        return try {
            wavFile.inputStream().use { input ->
                val header = ByteArray(44)
                input.read(header)
                ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to read WAV sample rate, defaulting to 22050")
            22050
        }
    }

    /**
     * Copy raw PCM data from a WAV file (skipping the header) to an output stream.
     * Returns the number of bytes written.
     */
    private fun copyPcmFromWav(wavFile: File, out: FileOutputStream): Long {
        return try {
            wavFile.inputStream().use { input ->
                // Skip WAV header (44 bytes for standard PCM WAV)
                val skipped = input.skip(44)
                if (skipped < 44) return 0L

                val buffer = ByteArray(65536)
                var totalWritten = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalWritten += bytesRead
                }
                totalWritten
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to extract PCM from WAV", e)
            0L
        }
    }

    // ── Utilities ────────────────────────────────────────────────

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(100)
    }
}