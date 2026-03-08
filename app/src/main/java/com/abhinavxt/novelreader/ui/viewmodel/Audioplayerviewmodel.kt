package com.abhinavxt.novelreader.ui.viewmodel

import android.content.Context
import android.media.MediaPlayer
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// ── Data models ──────────────────────────────────────────────────

data class AudioNovel(
    val folderName: String,
    val displayName: String,
    val chapters: List<AudioChapter>,
    val totalDurationMs: Long = 0
)

data class AudioChapter(
    val fileName: String,
    val displayName: String,
    val filePath: String,
    val fileSizeBytes: Long = 0
)

// ── Playback state ───────────────────────────────────────────────

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentChapter: AudioChapter? = null,
    val currentNovel: AudioNovel? = null,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1.0f,
    val isLoading: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────────────

class AudioPlayerViewModel(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AudioPlayerVM"
        private const val AUDIO_DIR = "NovelReader"
        private const val SEEK_STEP_MS = 10_000

        fun provideFactory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AudioPlayerViewModel(context.applicationContext) as T
                }
            }
        }
    }

    // Library state
    private val _novels = MutableStateFlow<List<AudioNovel>>(emptyList())
    val novels: StateFlow<List<AudioNovel>> = _novels.asStateFlow()

    // Playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    init {
        scanAudioFiles()
    }

    // ── Library scanning ─────────────────────────────────────────

    fun scanAudioFiles() {
        viewModelScope.launch {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val novelReaderDir = File(musicDir, AUDIO_DIR)

            if (!novelReaderDir.exists() || !novelReaderDir.isDirectory) {
                _novels.value = emptyList()
                return@launch
            }

            val novelList = novelReaderDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { novelDir ->
                    val chapters = novelDir.listFiles()
                        ?.filter { it.isFile && it.extension.lowercase() == "wav" }
                        ?.sortedBy { it.name }
                        ?.map { file ->
                            AudioChapter(
                                fileName = file.nameWithoutExtension,
                                displayName = file.nameWithoutExtension
                                    .replace("_", " ")
                                    .replaceFirstChar { it.uppercase() },
                                filePath = file.absolutePath,
                                fileSizeBytes = file.length()
                            )
                        }
                        ?: emptyList()

                    if (chapters.isNotEmpty()) {
                        AudioNovel(
                            folderName = novelDir.name,
                            displayName = novelDir.name
                                .replace("_", " ")
                                .replaceFirstChar { it.uppercase() },
                            chapters = chapters
                        )
                    } else null
                }
                ?.sortedBy { it.displayName }
                ?: emptyList()

            _novels.value = novelList
            Logger.d(TAG, "Found ${novelList.size} novels with audio")
        }
    }

    fun getNovel(folderName: String): AudioNovel? {
        return _novels.value.find { it.folderName == folderName }
    }

    // ── Playback controls ────────────────────────────────────────

    fun play(chapter: AudioChapter, novel: AudioNovel? = null) {
        // If same chapter is paused, just resume
        if (_playbackState.value.currentChapter?.filePath == chapter.filePath
            && mediaPlayer != null && !_playbackState.value.isPlaying) {
            resume()
            return
        }

        stop()

        _playbackState.value = _playbackState.value.copy(
            isLoading = true,
            currentChapter = chapter,
            currentNovel = novel
        )

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(chapter.filePath)
                setOnPreparedListener { mp ->
                    mp.playbackParams = mp.playbackParams.setSpeed(_playbackState.value.speed)
                    mp.start()
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = true,
                        isLoading = false,
                        durationMs = mp.duration,
                        positionMs = 0
                    )
                    startProgressTracking()
                }
                setOnCompletionListener {
                    onPlaybackComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Logger.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isLoading = false
                    )
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to play: ${chapter.filePath}", e)
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                isLoading = false
            )
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                stopProgressTracking()
            }
        }
    }

    fun resume() {
        mediaPlayer?.let {
            it.start()
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            startProgressTracking()
        }
    }

    fun togglePlayPause() {
        if (_playbackState.value.isPlaying) pause() else resume()
    }

    fun stop() {
        stopProgressTracking()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error stopping player: ${e.message}")
            }
        }
        mediaPlayer = null
        _playbackState.value = PlaybackState(speed = _playbackState.value.speed)
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let {
            it.seekTo(positionMs)
            _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
        }
    }

    fun skipForward() {
        val current = _playbackState.value
        val newPos = minOf(current.positionMs + SEEK_STEP_MS, current.durationMs)
        seekTo(newPos)
    }

    fun skipBackward() {
        val current = _playbackState.value
        val newPos = maxOf(current.positionMs - SEEK_STEP_MS, 0)
        seekTo(newPos)
    }

    fun setSpeed(speed: Float) {
        _playbackState.value = _playbackState.value.copy(speed = speed)
        mediaPlayer?.let {
            try {
                it.playbackParams = it.playbackParams.setSpeed(speed)
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to set speed: ${e.message}")
            }
        }
    }

    // ── Chapter navigation ───────────────────────────────────────

    fun playNextChapter() {
        val state = _playbackState.value
        val novel = state.currentNovel ?: return
        val currentIdx = novel.chapters.indexOfFirst { it.filePath == state.currentChapter?.filePath }
        if (currentIdx >= 0 && currentIdx < novel.chapters.size - 1) {
            play(novel.chapters[currentIdx + 1], novel)
        }
    }

    fun playPreviousChapter() {
        val state = _playbackState.value
        val novel = state.currentNovel ?: return
        val currentIdx = novel.chapters.indexOfFirst { it.filePath == state.currentChapter?.filePath }
        if (currentIdx > 0) {
            play(novel.chapters[currentIdx - 1], novel)
        }
    }

    private fun onPlaybackComplete() {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            positionMs = _playbackState.value.durationMs
        )
        stopProgressTracking()
        // Auto-play next chapter
        playNextChapter()
    }

    // ── Progress tracking ────────────────────────────────────────

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) {
                            _playbackState.value = _playbackState.value.copy(
                                positionMs = it.currentPosition
                            )
                        }
                    } catch (_: Exception) {}
                }
                delay(250)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}