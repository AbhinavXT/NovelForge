package com.abhinavxt.novelforge.data.tts

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates a chaptered M4B audiobook from exported WAV or AAC (.m4a) chapter files.
 *
 * M4B is an AAC-encoded MP4 container with chapter markers — the standard
 * format used by Apple Books, Smart Audiobook Player, and most podcast apps.
 * Compared to the merged WAV from [AudioMerger], M4B files are ~10x smaller
 * and support chapter navigation (tap to jump to Chapter 5).
 *
 * Pipeline:
 *   1. Read WAV files from Music/${AudioExporter.SUBFOLDER}/{novelTitle}/
 *   2. Encode PCM → AAC using [MediaCodec] (128kbps, LC profile)
 *   3. Mux into MP4 container using [MediaMuxer]
 *   4. Track chapter boundary timestamps during encoding
 *   5. Post-process: inject Nero `chpl` atom into the `moov` box
 *   6. Rename to .m4b
 *
 * ## Nero chapter format (`chpl` atom)
 * The most widely supported chapter format in MP4 files.
 * Lives inside moov → udta → chpl. Each chapter has a timestamp
 * (in 100-nanosecond units) and a UTF-8 title string.
 *
 * ## Usage
 * ```kotlin
 * val builder = M4BAudiobookBuilder(context)
 * builder.generateAudiobook("My Novel")
 * // Observe builder.state for progress
 * ```
 *
 * Requires chapters to be exported as WAV first via [AudioExporter].
 */
class M4BAudiobookBuilder(private val context: Context) {

    companion object {
        private const val TAG = "M4BBuilder"

        /** AAC encoding bitrate — 128kbps is standard for spoken word */
        private const val AAC_BITRATE = 128_000

        /** Silence between chapters in milliseconds */
        private const val CHAPTER_GAP_MS = 500

        /** MediaCodec buffer timeout in microseconds */
        private const val CODEC_TIMEOUT_US = 10_000L

        private const val RATE_MISMATCH = "Chapters were exported at different sample rates; export them with one voice"

        /**
         * Chapter files to build from: .wav and .m4a exports (not earlier audiobooks), optionally for
         * one voice, in chapter order. When a chapter exists in both formats the WAV is used.
         */
        internal fun pickChapterFiles(files: List<File>, sanitizedVoice: String?): List<File> =
            files.filter { it.isFile && it.extension in CHAPTER_AUDIO_EXTENSIONS && !it.name.contains("_audiobook") }
                .filter { sanitizedVoice == null || it.nameWithoutExtension.endsWith("_$sanitizedVoice", ignoreCase = true) }
                .sortedBy { if (it.extension == "wav") 0 else 1 }
                .distinctBy { it.nameWithoutExtension }
                .sortedBy { Regex("(\\d+)").find(it.nameWithoutExtension)?.value?.toIntOrNull() ?: 0 }

        /** Sample rate of a chapter file (WAV header or .m4a track), or -1 when unreadable. */
        private fun readSampleRate(file: File): Int = try {
            if (file.extension == "m4a") {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.absolutePath)
                    ex.getTrackFormat(0).getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } finally { ex.release() }
            } else {
                file.inputStream().use { input ->
                    val header = ByteArray(44)
                    input.read(header)
                    ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                }
            }
        } catch (e: Exception) {
            -1
        }

        /** Decode a mono .m4a to raw 16-bit PCM in [out]. */
        private suspend fun decodeM4aToPcm(file: File, out: File, expectedRate: Int) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val format = extractor.getTrackFormat(0)
                extractor.selectTrack(0)
                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var inputDone = false
                var outputDone = false
                java.io.FileOutputStream(out).use { os ->
                    val chunk = ByteArray(16384)
                    while (!outputDone) {
                        currentCoroutineContext().ensureActive()
                        if (!inputDone) {
                            val i = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (i >= 0) {
                                val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                                if (n < 0) {
                                    decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val o = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = decoder.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == expectedRate) { RATE_MISMATCH }
                            check(channels in 1..2) { "Unsupported channel layout in ${file.name}" }
                        } else if (o >= 0) {
                            val buf = decoder.getOutputBuffer(o)!!
                            buf.position(info.offset).limit(info.offset + info.size)
                            if (channels == 1) {
                                while (buf.hasRemaining()) {
                                    val n = minOf(chunk.size, buf.remaining())
                                    buf.get(chunk, 0, n)
                                    os.write(chunk, 0, n)
                                }
                            } else {
                                // Stereo: average left and right into mono 16-bit
                                val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val bb = ByteBuffer.allocate(shorts.remaining() / 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                                while (shorts.remaining() >= 2) bb.putShort(((shorts.get() + shorts.get()) / 2).toShort())
                                os.write(bb.array(), 0, bb.position())
                            }
                            decoder.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                extractor.release()
            }
        }

        // ── AAC encoding ────────────────────────────────────────────

        /** Raw 16-bit mono PCM in [file], after [skipBytes] of header. */
        class PcmInput(
            val file: File,
            val skipBytes: Long = 0,
            /** Runs just before the input is read (lazy decode); [file] is deleted after it is consumed. */
            val prepare: (suspend () -> Unit)? = null
        )

        /** Encode one PCM file to a single-track .m4a (used by the per-chapter AAC export). */
        suspend fun encodePcmFileToM4a(pcm: File, sampleRate: Int, bitrate: Int, outputFile: File) =
            encodePcmToM4a(listOf(PcmInput(pcm)), sampleRate, bitrate, outputFile)

        /**
         * Encode PCM inputs back to back (with a silent gap between them) to one AAC stream in an
         * M4A container. [onInputStart] gets each input's index and start time in microseconds.
         */
        suspend fun encodePcmToM4a(
            inputs: List<PcmInput>,
            sampleRate: Int,
            bitrate: Int,
            outputFile: File,
            onInputStart: (index: Int, startUs: Long) -> Unit = { _, _ -> }
        ) {
            // Configure AAC encoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            // Total PCM samples processed — used to calculate timestamps
            var totalSamplesWritten = 0L
            val bufferInfo = MediaCodec.BufferInfo()

            // Silence between chapters: N samples of zeros
            val silenceSamples = (sampleRate * CHAPTER_GAP_MS) / 1000
            val silenceBytes = ByteArray(silenceSamples * 2) // 16-bit = 2 bytes per sample

            try {
                for ((fileIndex, input) in inputs.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    // Record chapter start timestamp
                    val chapterStartUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    onInputStart(fileIndex, chapterStartUs)

                    // Feed PCM to encoder
                    input.prepare?.invoke()
                    FileInputStream(input.file).use { fis ->
                        fis.skip(input.skipBytes)

                        val readBuffer = ByteArray(8192)
                        var bytesRead: Int
                        var inputDone = false

                        while (!inputDone) {
                            currentCoroutineContext().ensureActive()
                            // Feed input
                            val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                                bytesRead = fis.read(readBuffer, 0, minOf(readBuffer.size, inputBuffer.remaining()))

                                if (bytesRead <= 0) {
                                    // No more data from this chapter
                                    encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                    inputDone = true
                                } else {
                                    inputBuffer.put(readBuffer, 0, bytesRead)
                                    val presentationTimeUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                                    encoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0)
                                    totalSamplesWritten += bytesRead / 2 // 16-bit = 2 bytes per sample
                                }
                            }

                            // Drain output
                            drainEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted).let { (ti, ms) ->
                                if (ti >= 0) trackIndex = ti
                                if (ms) muxerStarted = true
                            }
                        }
                    }

                    if (input.prepare != null) input.file.delete()

                    // Drain any remaining output after this chapter
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val result = drainEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted)
                        if (result.first >= 0) trackIndex = result.first
                        if (result.second) muxerStarted = true
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        val outIdx = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                        if (outIdx < 0 && outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
                        if (outIdx >= 0) {
                            val buf = encoder.getOutputBuffer(outIdx)!!
                            if (muxerStarted && bufferInfo.size > 0) {
                                muxer.writeSampleData(trackIndex, buf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                        }
                    }

                    // Add silence between chapters (not after the last one)
                    if (fileIndex < inputs.size - 1) {
                        feedPcmToEncoder(encoder, muxer, silenceBytes, totalSamplesWritten,
                            sampleRate, bufferInfo, trackIndex, muxerStarted).let { (ti, ms, sw) ->
                            if (ti >= 0) trackIndex = ti
                            if (ms) muxerStarted = true
                            totalSamplesWritten += sw
                        }
                    }
                }

                // Signal end of stream
                val finalInputIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (finalInputIdx >= 0) {
                    encoder.queueInputBuffer(finalInputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                // Final drain
                var draining = true
                while (draining) {
                    currentCoroutineContext().ensureActive()
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val buf = encoder.getOutputBuffer(outIdx)!!
                            if (muxerStarted && bufferInfo.size > 0) {
                                muxer.writeSampleData(trackIndex, buf, bufferInfo)
                            }
                            val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (isEos) draining = false
                        }
                        else -> draining = false
                    }
                }

            } finally {
                // Each step guarded so a failing stop() can't skip the releases or mask a cancel.
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }

            Logger.d(TAG, "Encoded ${inputs.size} inputs, total ${totalSamplesWritten} samples")
        }

        /**
         * Drain available output from the encoder and write to the muxer.
         * Returns (trackIndex, muxerStarted) — either or both may have changed.
         */
        private fun drainEncoder(
            encoder: MediaCodec,
            muxer: MediaMuxer,
            bufferInfo: MediaCodec.BufferInfo,
            trackIndex: Int,
            muxerStarted: Boolean
        ): Pair<Int, Boolean> {
            var ti = trackIndex
            var ms = muxerStarted

            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!ms) {
                        ti = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        ms = true
                    }
                }
                outIdx >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outIdx)!!
                    if (ms && bufferInfo.size > 0) {
                        muxer.writeSampleData(ti, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                }
            }
            return ti to ms
        }

        /**
         * Feed a block of PCM data (e.g. silence) to the encoder.
         * Returns (trackIndex, muxerStarted, samplesWritten).
         */
        private suspend fun feedPcmToEncoder(
            encoder: MediaCodec,
            muxer: MediaMuxer,
            pcmData: ByteArray,
            currentSamples: Long,
            sampleRate: Int,
            bufferInfo: MediaCodec.BufferInfo,
            trackIndex: Int,
            muxerStarted: Boolean
        ): Triple<Int, Boolean, Long> {
            var ti = trackIndex
            var ms = muxerStarted
            var offset = 0
            var samplesWritten = 0L

            while (offset < pcmData.size) {
                currentCoroutineContext().ensureActive()
                val inputIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIdx >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIdx)!!
                    val bytesToWrite = minOf(pcmData.size - offset, inputBuffer.remaining())
                    inputBuffer.put(pcmData, offset, bytesToWrite)
                    val pts = ((currentSamples + samplesWritten) * 1_000_000L) / sampleRate
                    encoder.queueInputBuffer(inputIdx, 0, bytesToWrite, pts, 0)
                    offset += bytesToWrite
                    samplesWritten += bytesToWrite / 2
                }
                // Drain output while feeding
                drainEncoder(encoder, muxer, bufferInfo, ti, ms).let { (t, m) ->
                    if (t >= 0) ti = t
                    if (m) ms = true
                }
            }
            return Triple(ti, ms, samplesWritten)
        }
    }

    // ── Observable state ────────────────────────────────────────

    sealed interface BuildState {
        object Idle : BuildState
        data class Building(
            val phase: String,
            val currentChapter: Int,
            val totalChapters: Int,
            val progress: Float
        ) : BuildState
        data class Complete(val filePath: String, val fileSizeBytes: Long) : BuildState
        data class Error(val message: String) : BuildState
    }

    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    val state: StateFlow<BuildState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

    // ── Public API ──────────────────────────────────────────────

    /**
     * Generate a chaptered M4B audiobook from exported WAV files.
     *
     * Reads WAV files from Music/${AudioExporter.SUBFOLDER}/[novelTitle]/, encodes them
     * to AAC, creates a single .m4b with chapter markers.
     *
     * @param novelTitle  Folder name under Music/${AudioExporter.SUBFOLDER}/
     * @param voiceFilter Optional — only use WAV files from this voice
     */
    fun launch(novelTitle: String, voiceFilter: String? = null) {
        currentJob?.cancel()
        currentJob = scope.launch {
            generateAudiobook(novelTitle, voiceFilter)
        }
        ExportForegroundService.begin(context)
        currentJob!!.invokeOnCompletion { ExportForegroundService.end() }
    }

    fun cancel() {
        currentJob?.cancel()
        _state.value = BuildState.Idle
    }

    fun reset() {
        _state.value = BuildState.Idle
    }

    // ── Core pipeline ───────────────────────────────────────────

    private suspend fun generateAudiobook(
        novelTitle: String,
        voiceFilter: String?
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Find and sort WAV chapter files
            val baseDir = AudioExporter.novelAudioDir(sanitizeFilename(novelTitle))

            if (!baseDir.exists() || !baseDir.isDirectory) {
                _state.value = BuildState.Error("No exported audio found for \"$novelTitle\"")
                return@withContext
            }

            val wavFiles = pickChapterFiles(
                baseDir.listFiles()?.toList() ?: emptyList(),
                voiceFilter?.let { sanitizeFilename(it) }
            )

            if (wavFiles.isEmpty()) {
                _state.value = BuildState.Error("No exported chapter audio found. Export chapters first.")
                return@withContext
            }

            if (wavFiles.size < 2) {
                _state.value = BuildState.Error("Need at least 2 chapters to build an audiobook.")
                return@withContext
            }

            Logger.d(TAG, "Building M4B from ${wavFiles.size} chapter files")

            // 2. All chapters must share one sample rate (the voice's)
            val rates = wavFiles.map { readSampleRate(it) }
            val sampleRate = rates.first()
            if (sampleRate <= 0) {
                _state.value = BuildState.Error("Cannot read audio format from chapter files")
                return@withContext
            }
            if (rates.any { it != sampleRate }) {
                _state.value = BuildState.Error(RATE_MISMATCH)
                return@withContext
            }

            // 3. Create temp M4A file, encode all chapters (.m4a ones are decoded to temp PCM first)
            val tempM4a = File(context.cacheDir, "audiobook_${System.currentTimeMillis()}.m4a")
            val chapters = mutableListOf<ChapterMark>()
            val tempPcm = mutableListOf<File>()

            try {
                val inputs = wavFiles.mapIndexed { i, f ->
                    if (f.extension == "wav") PcmInput(f, 44) else {
                        val pcm = File(context.cacheDir, "m4b_dec_${System.currentTimeMillis()}_$i.pcm")
                        tempPcm.add(pcm)
                        // Decoded just before it is encoded and deleted right after, so cache use stays one chapter
                        PcmInput(pcm, 0) {
                            _state.value = BuildState.Building("Decoding", i + 1, wavFiles.size, i.toFloat() / wavFiles.size)
                            decodeM4aToPcm(f, pcm, sampleRate)
                        }
                    }
                }
                encodePcmToM4a(inputs, sampleRate, AAC_BITRATE, tempM4a) { index, startUs ->
                    chapters.add(ChapterMark(extractChapterTitle(wavFiles[index]), startUs))
                    _state.value = BuildState.Building(
                        phase = "Encoding",
                        currentChapter = index + 1,
                        totalChapters = wavFiles.size,
                        progress = index.toFloat() / wavFiles.size
                    )
                }

                ensureActive()

                // 4. Inject chapter markers into the MP4 container
                _state.value = BuildState.Building(
                    phase = "Adding chapter markers",
                    currentChapter = wavFiles.size,
                    totalChapters = wavFiles.size,
                    progress = 0.95f
                )
                injectChapterMarkers(tempM4a, chapters)

                ensureActive()

                // 5. Save to Music/${AudioExporter.SUBFOLDER}/ as .m4b
                _state.value = BuildState.Building(
                    phase = "Saving audiobook",
                    currentChapter = wavFiles.size,
                    totalChapters = wavFiles.size,
                    progress = 0.98f
                )

                val voiceSuffix = if (voiceFilter != null) "_${sanitizeFilename(voiceFilter)}" else ""
                val outputName = "${sanitizeFilename(novelTitle)}${voiceSuffix}_audiobook.m4b"
                val outputPath = saveToMusic(baseDir.name, outputName, tempM4a)

                if (outputPath != null) {
                    val fileSize = tempM4a.length()
                    Logger.d(TAG, "M4B audiobook created: $outputPath (${fileSize / 1024}KB, ${chapters.size} chapters)")
                    _state.value = BuildState.Complete(outputPath, fileSize)
                } else {
                    _state.value = BuildState.Error("Failed to save M4B file")
                }

            } finally {
                tempM4a.delete()
                tempPcm.forEach { it.delete() }
            }

        } catch (e: CancellationException) {
            Logger.d(TAG, "M4B build cancelled")
            _state.value = BuildState.Idle
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "M4B build failed", e)
            _state.value = BuildState.Error("Build failed: ${e.message}")
        }
    }

    // ── Chapter marker injection ────────────────────────────────

    /**
     * Inject Nero-format chapter markers (`chpl` atom) into the MP4 file.
     *
     * The `chpl` atom goes inside moov → udta. Since [MediaMuxer] puts
     * `moov` at the end of the file, we can append `udta` + `chpl` to
     * `moov` without shifting any data — just update the `moov` size.
     *
     * Nero `chpl` format (version 1):
     *   - version: uint8 = 1
     *   - flags: uint24 = 0
     *   - reserved: uint8 = 0
     *   - chapterCount: uint32
     *   - per chapter:
     *       timestamp: int64 (100-nanosecond units)
     *       titleLength: uint8
     *       title: UTF-8 bytes
     */
    private fun injectChapterMarkers(file: File, chapters: List<ChapterMark>) {
        if (chapters.isEmpty()) return

        // Build the chpl atom content
        val chplBody = buildChplAtom(chapters)

        // Build udta atom: [size:4][type:4='udta'][chpl atom]
        val udtaSize = 8 + chplBody.size
        val udtaAtom = ByteBuffer.allocate(udtaSize).order(ByteOrder.BIG_ENDIAN)
        udtaAtom.putInt(udtaSize)
        udtaAtom.put("udta".toByteArray(Charsets.US_ASCII))
        udtaAtom.put(chplBody)
        udtaAtom.flip()

        RandomAccessFile(file, "rw").use { raf ->
            // Find moov atom
            val moovPos = findAtom(raf, "moov")
            if (moovPos < 0) {
                Logger.e(TAG, "Cannot find moov atom in MP4 file")
                return
            }

            // Read current moov size
            raf.seek(moovPos)
            val currentMoovSize = raf.readInt().toLong() and 0xFFFFFFFFL

            // Check if udta already exists inside moov
            val udtaPos = findAtomInside(raf, "udta", moovPos + 8, moovPos + currentMoovSize)

            if (udtaPos >= 0) {
                // udta exists — append chpl inside it
                raf.seek(udtaPos)
                val currentUdtaSize = raf.readInt().toLong() and 0xFFFFFFFFL
                val newUdtaSize = currentUdtaSize + chplBody.size

                // Update udta size
                raf.seek(udtaPos)
                raf.writeInt(newUdtaSize.toInt())

                // Append chpl at end of udta content
                raf.seek(udtaPos + currentUdtaSize)
                raf.write(chplBody)

                // Update moov size
                val newMoovSize = currentMoovSize + chplBody.size
                raf.seek(moovPos)
                raf.writeInt(newMoovSize.toInt())
            } else {
                // No udta — append entire udta+chpl at end of moov
                val moovEnd = moovPos + currentMoovSize
                raf.seek(moovEnd)
                raf.write(udtaAtom.array())

                // Update moov size
                val newMoovSize = currentMoovSize + udtaSize
                raf.seek(moovPos)
                raf.writeInt(newMoovSize.toInt())
            }
        }

        Logger.d(TAG, "Injected ${chapters.size} chapter markers into M4B")
    }

    /**
     * Build the raw bytes for a Nero `chpl` atom (without the atom header).
     * Returns: [size:4][type:4='chpl'][version:1][flags:3][reserved:1][count:4]
     */
    private fun buildChplAtom(chapters: List<ChapterMark>): ByteArray {
        // Calculate total size
        var chapterDataSize = 0
        for (ch in chapters) {
            val titleBytes = ch.title.toByteArray(Charsets.UTF_8)
            chapterDataSize += 8 + 1 + titleBytes.size // timestamp(8) + titleLen(1) + title
        }

        // chpl atom: header(8) + version(1) + flags(3) + reserved(1) + count(4) + chapters
        val totalSize = 8 + 1 + 3 + 1 + 4 + chapterDataSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Atom header
        buf.putInt(totalSize)
        buf.put("chpl".toByteArray(Charsets.US_ASCII))

        // Version 1
        buf.put(1.toByte())
        // Flags (3 bytes of zeros)
        buf.put(0); buf.put(0); buf.put(0)
        // Reserved (version 1 only)
        buf.put(0)
        // Chapter count
        buf.putInt(chapters.size)

        // Each chapter
        for (ch in chapters) {
            // Timestamp in 100-nanosecond units (microseconds * 10)
            val timestamp100ns = ch.timestampUs * 10L
            buf.putLong(timestamp100ns)

            val titleBytes = ch.title.toByteArray(Charsets.UTF_8)
            val titleLen = titleBytes.size.coerceAtMost(255)
            buf.put(titleLen.toByte())
            buf.put(titleBytes, 0, titleLen)
        }

        return buf.array()
    }

    // ── MP4 atom scanning ───────────────────────────────────────

    /**
     * Find a top-level atom by its 4-char type name.
     * Returns the file offset of the atom's size field, or -1 if not found.
     */
    private fun findAtom(raf: RandomAccessFile, atomType: String): Long {
        raf.seek(0)
        val typeBytes = atomType.toByteArray(Charsets.US_ASCII)
        val headerBuf = ByteArray(8)

        while (raf.filePointer < raf.length()) {
            val atomPos = raf.filePointer
            if (raf.read(headerBuf) != 8) break

            var size = ByteBuffer.wrap(headerBuf, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = headerBuf.copyOfRange(4, 8)

            if (type.contentEquals(typeBytes)) {
                return atomPos
            }

            // size == 1: the real size follows as 64 bits (MediaMuxer writes mdat this way on newer Android)
            if (size == 1L) size = raf.readLong()

            // Move to next atom
            if (size < 8) break // Invalid
            raf.seek(atomPos + size)
        }
        return -1
    }

    /**
     * Find a child atom inside a parent atom's content.
     * Searches from [startPos] to [endPos] (exclusive).
     */
    private fun findAtomInside(raf: RandomAccessFile, atomType: String, startPos: Long, endPos: Long): Long {
        raf.seek(startPos)
        val typeBytes = atomType.toByteArray(Charsets.US_ASCII)
        val headerBuf = ByteArray(8)

        while (raf.filePointer < endPos) {
            val atomPos = raf.filePointer
            if (raf.read(headerBuf) != 8) break

            val size = ByteBuffer.wrap(headerBuf, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = headerBuf.copyOfRange(4, 8)

            if (type.contentEquals(typeBytes)) {
                return atomPos
            }

            if (size < 8) break
            raf.seek(atomPos + size)
        }
        return -1
    }

    // ── Chapter file helpers ────────────────────────────────────

    /**
     * Extract a readable chapter title from the WAV filename.
     * "Chapter_3_en-US-Journey-Neural2-F.wav" → "Chapter 3"
     */
    private fun extractChapterTitle(wavFile: File): String {
        val name = wavFile.nameWithoutExtension
        // Try to find "Chapter_N" pattern
        val match = Regex("(?i)(chapter)[_\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(name)
        return if (match != null) {
            "Chapter ${match.groupValues[2]}"
        } else {
            // Fallback: use the number found in the filename
            val num = Regex("(\\d+)").find(name)?.value
            if (num != null) "Chapter $num" else name.replace("_", " ")
        }
    }

    /**
     * Sanitize a string for use as a filename/folder name.
     * Must match [AudioExporter.sanitizeFilename] exactly — the WAV files
     * are saved using that function, so we need to find them with the same name.
     */
    private fun sanitizeFilename(name: String) = AudioExporter.sanitizeName(name)

    // ── File output ─────────────────────────────────────────────

    /**
     * Save the M4B file to Music/${AudioExporter.SUBFOLDER}/{novelFolder}/.
     * Uses MediaStore on Android 10+, direct file I/O on older versions.
     */
    private suspend fun saveToMusic(novelFolder: String, fileName: String, tempFile: File): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(novelFolder, fileName, tempFile)
        } else {
            saveDirectly(novelFolder, fileName, tempFile)
        }
    }

    private suspend fun saveViaMediaStore(novelFolder: String, fileName: String, tempFile: File): String? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/${AudioExporter.SUBFOLDER}/$novelFolder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input -> copyCancellable(input, out) }
            }
        } catch (e: Throwable) {
            // Cancelled or failed mid-copy: drop the half-written pending row.
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        return "Music/${AudioExporter.SUBFOLDER}/$novelFolder/$fileName"
    }

    private suspend fun saveDirectly(novelFolder: String, fileName: String, tempFile: File): String? {
        val dir = AudioExporter.novelAudioDir(novelFolder)
        dir.mkdirs()
        val outputFile = File(dir, fileName)
        // Write beside the target and rename on success so a cancel never destroys an existing audiobook.
        val partial = File(dir, "$fileName.part")
        try {
            FileInputStream(tempFile).use { input ->
                partial.outputStream().use { out -> copyCancellable(input, out) }
            }
            if (!partial.renameTo(outputFile)) {
                partial.copyTo(outputFile, overwrite = true)
            }
        } finally {
            partial.delete()
        }
        return outputFile.absolutePath
    }

    private suspend fun copyCancellable(input: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    // ── Data classes ────────────────────────────────────────────

    /**
     * A chapter marker with its start timestamp and title.
     */
    private data class ChapterMark(
        val title: String,
        val timestampUs: Long  // microseconds from start of audiobook
    )
}