package com.abhinavxt.novelforge.data.tts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Merges individual exported WAV chapter files into a single audiobook WAV.
 *
 * Reads from Music/NovelReader/{novelTitle}/, sorts chapters by filename,
 * verifies matching sample rates, concatenates PCM data, and writes a
 * combined WAV file.
 *
 * Output: Music/NovelReader/{novelTitle}/{novelTitle}_audiobook.wav
 */
class AudioMerger(private val context: Context) {

    sealed interface MergeState {
        object Idle : MergeState
        data class Merging(val currentChapter: Int, val totalChapters: Int) : MergeState
        data class Complete(val filePath: String, val totalDurationMs: Long) : MergeState
        data class Error(val message: String) : MergeState
    }

    private val _state = MutableStateFlow<MergeState>(MergeState.Idle)
    val state: StateFlow<MergeState> = _state.asStateFlow()

    /**
     * Merge all WAV files in a novel's export directory into one audiobook.
     *
     * @param novelTitle The novel folder name under Music/NovelReader/
     * @param voiceFilter Optional voice name to filter — only merge files for this voice
     */
    suspend fun mergeChapters(
        novelTitle: String,
        voiceFilter: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "novelforge/$novelTitle"
            )

            if (!baseDir.exists() || !baseDir.isDirectory) {
                _state.value = MergeState.Error("No exported audio found for \"$novelTitle\"")
                return@withContext null
            }

            // Find all WAV files, optionally filtered by voice
            val wavFiles = baseDir.listFiles()
                ?.filter { it.isFile && it.extension == "wav" && !it.name.contains("_audiobook") }
                ?.filter { file ->
                    if (voiceFilter != null) {
                        file.nameWithoutExtension.endsWith("_$voiceFilter", ignoreCase = true)
                    } else true
                }
                ?.sortedBy { file ->
                    // Sort by chapter number extracted from filename
                    // Format: "Chapter_1_VoiceName.wav" or similar
                    val numMatch = Regex("(\\d+)").find(file.nameWithoutExtension)
                    numMatch?.value?.toIntOrNull() ?: 0
                }
                ?: emptyList()

            if (wavFiles.isEmpty()) {
                _state.value = MergeState.Error("No WAV files found to merge")
                return@withContext null
            }

            if (wavFiles.size == 1) {
                _state.value = MergeState.Error("Only 1 chapter found — nothing to merge")
                return@withContext null
            }

            Logger.d(TAG, "Merging ${wavFiles.size} WAV files for \"$novelTitle\"")

            // Read sample rate from first file to verify consistency
            val referenceSampleRate = readWavSampleRate(wavFiles.first())
            if (referenceSampleRate <= 0) {
                _state.value = MergeState.Error("Could not read audio format from first chapter")
                return@withContext null
            }

            // Verify all files have same sample rate
            for (file in wavFiles) {
                val sr = readWavSampleRate(file)
                if (sr != referenceSampleRate) {
                    _state.value = MergeState.Error(
                        "Sample rate mismatch: ${file.name} is ${sr}Hz but expected ${referenceSampleRate}Hz. " +
                                "Re-export all chapters with the same voice."
                    )
                    return@withContext null
                }
            }

            // Calculate total PCM size
            var totalPcmBytes = 0L
            for (file in wavFiles) {
                totalPcmBytes += file.length() - 44 // Subtract WAV header
            }

            // Add 0.5s silence between chapters
            val silenceSamplesPerGap = (referenceSampleRate * 0.5).toInt()
            val silenceBytesPerGap = silenceSamplesPerGap * 2 // 16-bit = 2 bytes per sample
            val totalSilenceBytes = silenceBytesPerGap.toLong() * (wavFiles.size - 1)
            totalPcmBytes += totalSilenceBytes

            // Create temp file for merged PCM
            val tempFile = File.createTempFile("audiobook_merge", ".tmp", context.cacheDir)

            try {
                // Write all PCM data to temp file
                FileOutputStream(tempFile).use { out ->
                    val silenceBuffer = ByteArray(silenceBytesPerGap)
                    val copyBuffer = ByteArray(8192)

                    wavFiles.forEachIndexed { index, wavFile ->
                        _state.value = MergeState.Merging(index + 1, wavFiles.size)
                        Logger.d(TAG, "Merging chapter ${index + 1}/${wavFiles.size}: ${wavFile.name}")

                        FileInputStream(wavFile).use { input ->
                            // Skip WAV header (44 bytes)
                            input.skip(44)

                            // Copy PCM data
                            var bytesRead: Int
                            while (input.read(copyBuffer).also { bytesRead = it } > 0) {
                                out.write(copyBuffer, 0, bytesRead)
                            }
                        }

                        // Add silence gap between chapters (not after last)
                        if (index < wavFiles.size - 1) {
                            out.write(silenceBuffer)
                        }
                    }
                }

                // Write final WAV file
                val voiceSuffix = if (voiceFilter != null) "_$voiceFilter" else ""
                val outputName = "${novelTitle}${voiceSuffix}_audiobook.wav"

                val outputPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeViaMediaStore(baseDir.name, outputName, tempFile, totalPcmBytes, referenceSampleRate)
                } else {
                    writeDirectly(baseDir, outputName, tempFile, totalPcmBytes, referenceSampleRate)
                }

                val durationMs = (totalPcmBytes * 1000) / (referenceSampleRate * 2) // 16-bit mono

                Logger.d(TAG, "Audiobook created: $outputPath (${totalPcmBytes / 1024}KB, ${durationMs / 1000}s)")
                _state.value = MergeState.Complete(outputPath ?: outputName, durationMs)

                outputPath
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Merge failed", e)
            _state.value = MergeState.Error("Merge failed: ${e.message}")
            null
        }
    }

    fun reset() {
        _state.value = MergeState.Idle
    }

    // ── WAV helpers ──────────────────────────────────────────────

    private fun readWavSampleRate(file: File): Int {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(44)
                input.read(header)
                ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun writeViaMediaStore(
        novelFolder: String,
        fileName: String,
        tempFile: File,
        pcmSize: Long,
        sampleRate: Int
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/novelforge/$novelFolder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        context.contentResolver.openOutputStream(uri)?.use { out ->
            writeWavHeader(out, pcmSize.toInt(), sampleRate)
            FileInputStream(tempFile).use { input ->
                input.copyTo(out, 8192)
            }
        }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        return "Music/novelforge/$novelFolder/$fileName"
    }

    private fun writeDirectly(
        novelDir: File,
        fileName: String,
        tempFile: File,
        pcmSize: Long,
        sampleRate: Int
    ): String? {
        val outputFile = File(novelDir, fileName)
        FileOutputStream(outputFile).use { out ->
            writeWavHeader(out, pcmSize.toInt(), sampleRate)
            FileInputStream(tempFile).use { input ->
                input.copyTo(out, 8192)
            }
        }
        return outputFile.absolutePath
    }

    private fun writeWavHeader(outputStream: OutputStream, dataSize: Int, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        outputStream.write(header.array())
    }

    companion object {
        private const val TAG = "AudioMerger"
    }
}