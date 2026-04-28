package com.abhinavxt.novelreader.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.util.Logger

/**
 * Manages audio focus on behalf of TTS playback.
 *
 * ── Why this exists ──
 *
 * When the user takes a phone call, opens a voice assistant, hits "send"
 * on a voice note, or starts playing music in another app, our TTS
 * should immediately pause/duck. When the interruption ends, we should
 * intelligently decide whether to resume.
 *
 * Without audio focus, TTS just keeps droning under the phone call.
 * Users hate this. Android considers it one of the top reasons apps get
 * uninstalled in the "audio playback" category.
 *
 * ── Focus loss levels, explained ──
 *
 *   AUDIOFOCUS_LOSS            — permanent loss. Another app has taken
 *                                over audio (music player started,
 *                                phone call picked up). We pause and
 *                                abandon focus. We do NOT auto-resume
 *                                when we regain focus — user decides.
 *
 *   AUDIOFOCUS_LOSS_TRANSIENT  — short interruption (notification beep,
 *                                turn-by-turn voice direction that
 *                                doesn't duck). We pause. When we get
 *                                GAIN again, we auto-resume.
 *
 *   AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
 *                              — another app wants to play briefly but
 *                                is okay with us continuing quietly.
 *                                We duck to ~20% volume. Restore on GAIN.
 *
 *   AUDIOFOCUS_GAIN            — we have focus again. Un-duck if ducked,
 *                                resume if paused-because-of-transient.
 *
 * ── Phone calls specifically ──
 *
 * Incoming calls dispatch AUDIOFOCUS_LOSS. We pause. When the call ends,
 * we get AUDIOFOCUS_GAIN. But we track WHY we paused — if it was due
 * to a LOSS (not LOSS_TRANSIENT), we stay paused. A 20-minute phone
 * call shouldn't auto-resume an audiobook the user may have forgotten
 * they started.
 *
 * Users who want explicit resume can tap Play in the notification, hit
 * the headset button, or return to the app.
 */
class AudioFocusHelper(
    private val context: Context,
    private val ttsManager: TTSManager
) {

    companion object {
        private const val TAG = "AudioFocusHelper"

        // Volume level while ducking. ~20% is Android convention; quiet
        // enough to hear the interruption, loud enough to not pretend
        // we're off.
        private const val DUCK_VOLUME = 0.2f
        private const val NORMAL_VOLUME = 1.0f
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Modern API only exists on Oreo+. For older devices we fall back
    // to the deprecated requestAudioFocus(listener, stream, hint).
    private var focusRequest: AudioFocusRequest? = null

    // State tracked across focus transitions.
    //
    // pausedByFocusLoss: true if we paused BECAUSE of focus loss
    //   (transient or permanent). Distinguishes "user paused manually"
    //   from "we paused you because of an interruption" — only the
    //   latter should auto-resume.
    //
    // shouldResumeOnRegain: only true if the loss was TRANSIENT.
    //   Permanent loss stays paused.
    //
    // currentlyDucked: true while volume is lowered. Used so we don't
    //   re-apply duck volume on every repeated LOSS_TRANSIENT_CAN_DUCK
    //   (which can fire multiple times during a single interruption).
    @Volatile private var pausedByFocusLoss = false
    @Volatile private var shouldResumeOnRegain = false
    @Volatile private var currentlyDucked = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Logger.d(TAG, "AUDIOFOCUS_GAIN — regained focus")
                // Un-duck first if we were ducking. Volume restore is cheap
                // even if we weren't actually ducked.
                if (currentlyDucked) {
                    ttsManager.setVolume(NORMAL_VOLUME)
                    currentlyDucked = false
                }
                // Resume ONLY if we paused due to a transient loss. A
                // permanent LOSS (phone call, other media) leaves TTS
                // paused; user must resume manually.
                if (pausedByFocusLoss && shouldResumeOnRegain) {
                    Logger.d(TAG, "Auto-resuming after transient interruption")
                    ttsManager.resume()
                }
                pausedByFocusLoss = false
                shouldResumeOnRegain = false
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Logger.d(TAG, "AUDIOFOCUS_LOSS — permanent, pausing")
                // Permanent loss. Pause if playing, remember why.
                if (ttsManager.isPlaying()) {
                    ttsManager.pause()
                    pausedByFocusLoss = true
                    shouldResumeOnRegain = false   // no auto-resume
                }
                // Abandon focus — we're done. If we don't abandon, the
                // system may think we're still a contender for focus.
                abandon()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Logger.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT — pausing for interruption")
                if (ttsManager.isPlaying()) {
                    ttsManager.pause()
                    pausedByFocusLoss = true
                    shouldResumeOnRegain = true    // auto-resume on GAIN
                }
                // We keep focus request active — the system will fire
                // GAIN when the interruption ends.
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Logger.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK — ducking")
                // Duck rather than pause. TTS continues quietly under
                // the interruption (e.g. a nav voice direction).
                if (!currentlyDucked) {
                    ttsManager.setVolume(DUCK_VOLUME)
                    currentlyDucked = true
                }
            }
        }
    }

    /**
     * Request audio focus. Called by the foreground service when TTS
     * starts speaking.
     *
     * Returns true if focus was granted. If false, the caller should
     * still let TTS play — denial is rare and users expect tapping
     * play to always work — but it's a signal that audio may behave
     * weirdly (another app is locked in exclusive audio mode, very
     * uncommon).
     */
    fun request(): Boolean {
        // Reset state for a fresh playback session.
        pausedByFocusLoss = false
        shouldResumeOnRegain = false
        currentlyDucked = false

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestModern()
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        Logger.d(TAG, "Focus request granted=$granted")
        return granted
    }

    /**
     * Release audio focus. Call when TTS stops or pauses explicitly
     * (user-initiated pause, not focus-loss pause — we want to KEEP
     * focus during a focus-loss pause so we can get GAIN back).
     */
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        // Reset state so next request() starts clean.
        pausedByFocusLoss = false
        shouldResumeOnRegain = false
        currentlyDucked = false
    }

    // ─── Modern (Oreo+) focus request ──────────────────────────────

    private fun requestModern(): Boolean {
        // AudioAttributes tell the system what KIND of audio we're playing.
        // USAGE_MEDIA + CONTENT_TYPE_SPEECH is the correct combo for an
        // audiobook/TTS scenario. This influences:
        //   - How other apps duck us (nav voice ducks CONTENT_TYPE_MUSIC
        //     harder than CONTENT_TYPE_SPEECH, since it assumes speech
        //     content needs to stay intelligible)
        //   - Routing to headset vs speaker vs Bluetooth
        //   - Display in the volume UI ("Media volume")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)   // we handle our own ducking
            .setOnAudioFocusChangeListener(focusListener)
            .build()

        focusRequest = request
        return audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}