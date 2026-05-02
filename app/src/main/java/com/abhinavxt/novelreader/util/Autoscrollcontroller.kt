package com.abhinavxt.novelreader.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Auto-scroll controller for the reader's scroll mode (LazyColumn).
 *
 * Drives a frame-aligned, pixel-by-pixel drift down the page — the
 * teleprompter feel. Decoupled from TTS, decoupled from the reader's
 * tap/swipe handlers, and lifecycle-agnostic (the caller owns when it
 * runs and when it's cancelled).
 *
 * ── Why a manual frame loop instead of animateScrollBy ──
 *
 * `LazyListState.animateScrollBy(target)` is animation-shaped: you
 * give it a target offset and a duration, it interpolates. Chaining
 * animations to fake continuous drift jitters at the boundaries
 * because each new animation re-resolves the velocity curve from rest.
 *
 * The teleprompter feel needs *constant velocity*. The right primitive
 * is `withFrameNanos { ... }` — a callback that fires once per draw
 * frame (typically 16.67ms @ 60Hz, or shorter on 90/120Hz panels). We
 * compute "how many pixels should I have moved since the last frame"
 * from the delta-t, and call `scrollState.scrollBy(deltaPx)`. This
 * stays smooth across refresh-rate changes and never accumulates
 * timing drift.
 *
 * ── State machine ──
 *
 *   IDLE         — controller is dormant. start() → ACTIVE.
 *   ACTIVE       — scroll loop running. pause() → PAUSED.
 *                                       stop() → IDLE.
 *                                       Hit end of list → CHAPTER_END.
 *   PAUSED       — loop suspended, position kept. resume() → ACTIVE.
 *                                                  stop() → IDLE.
 *   CHAPTER_END  — list exhausted, waiting on caller to advance
 *                  chapter or stop. Caller calls onChapterEnd
 *                  callback; if they advance, they call
 *                  onChapterAdvanced() to give the controller a
 *                  fresh ACTIVE start with a brief pause-at-top.
 *
 * The state is exposed as a StateFlow so UI can observe it (e.g. show
 * "play" vs "pause" icon, dim chrome during scroll, etc).
 *
 * ── Lifecycle ──
 *
 * The controller does NOT own a CoroutineScope. The caller passes one
 * in (typically `rememberCoroutineScope()` from the reader screen) so
 * cancellation follows screen lifecycle automatically — no leak risk.
 */
class AutoScrollController(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
) {
    enum class State { IDLE, ACTIVE, PAUSED, CHAPTER_END }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Speed in display pixels per second.
     *
     * Why px/sec instead of "lines per minute" or "scroll percent":
     * px/sec is the primitive the scroll API works in, and it's
     * device-density-independent in the only way that actually matters
     * for reading — text rendered at the same dp size on two devices
     * occupies the same number of px-per-second to scroll at the same
     * *visual* speed. Lines-per-minute would have to be recomputed
     * every time the user changes font size or line spacing, which is
     * fragile. px/sec is honest.
     *
     * Default 60 px/sec ≈ comfortable reading at 16sp font / 1.6 line
     * height. Tested empirically — at 16sp / 1.6 lineSpacing, one line
     * of text occupies ~26dp ≈ 78px on a density-3 phone, so 60px/sec
     * is roughly one line every 1.3 seconds, which is a slow-but-
     * comfortable rate for English prose at ~300 WPM equivalent.
     */
    private val _speedPxPerSec = MutableStateFlow(60f)
    val speedPxPerSec: StateFlow<Float> = _speedPxPerSec.asStateFlow()

    /**
     * Reference to the running scroll job. Held so we can cancel
     * cleanly in pause/stop without the loop continuing through
     * one extra frame.
     */
    private var scrollJob: Job? = null

    /**
     * Callback the controller fires when the scroll hits the end of
     * the chapter. The caller decides whether to advance or stop.
     * If the caller advances, they should call [onChapterAdvanced].
     */
    private var chapterEndCallback: (() -> Unit)? = null

    // ─── Public API ────────────────────────────────────────────────

    /**
     * Begin auto-scroll. No-op if already active.
     *
     * @param onChapterEnd Fired (on the main coroutine) when the scroll
     *   reaches the end of the LazyColumn — i.e. the last item is fully
     *   visible and there's nothing left to scroll. Caller should
     *   either invoke a chapter-advance and call [onChapterAdvanced],
     *   or call [stop] to exit the mode.
     */
    fun start(onChapterEnd: () -> Unit) {
        if (_state.value == State.ACTIVE) return
        chapterEndCallback = onChapterEnd
        _state.value = State.ACTIVE
        scrollJob = scope.launch { runScrollLoop() }
    }

    /** Pause the scroll loop in place. State preserved. */
    fun pause() {
        if (_state.value != State.ACTIVE) return
        _state.value = State.PAUSED
        scrollJob?.cancel()
        scrollJob = null
    }

    /** Resume from PAUSED. No-op otherwise. */
    fun resume() {
        if (_state.value != State.PAUSED) return
        _state.value = State.ACTIVE
        scrollJob = scope.launch { runScrollLoop() }
    }

    /** Stop entirely. Returns to IDLE; caller exits auto-scroll mode. */
    fun stop() {
        _state.value = State.IDLE
        scrollJob?.cancel()
        scrollJob = null
        chapterEndCallback = null
    }

    /**
     * Caller has navigated to the next chapter. Give the user a brief
     * 2-second pause at the top of the new chapter (so the brain can
     * reset) before resuming the scroll loop. If the caller doesn't
     * call this after a chapter-end fires, the controller stays in
     * CHAPTER_END until [stop] is called.
     */
    fun onChapterAdvanced() {
        if (_state.value != State.CHAPTER_END) return
        scrollJob = scope.launch {
            // The 2-second beat. delay() is cancellation-cooperative,
            // so if the user taps the screen during the pause it'll
            // be interrupted by [pause] / [stop].
            //
            // Note: we set state ACTIVE *before* the delay so the UI
            // reflects "scrolling soon" not "stuck at chapter end".
            // The user sees the play icon, the scroll begins after
            // 2s. If we set ACTIVE after the delay, the icon would
            // sit on the wrong state for 2 seconds.
            _state.value = State.ACTIVE
            delay(CHAPTER_RESUME_DELAY_MS)
            runScrollLoop()
        }
    }

    /** Toggle between ACTIVE and PAUSED. Used by tap-on-content. */
    fun togglePause() {
        when (_state.value) {
            State.ACTIVE -> pause()
            State.PAUSED -> resume()
            else -> Unit  // IDLE / CHAPTER_END — tap-pause is a no-op
        }
    }

    /** Set scroll speed. Clamped to safe range. */
    fun setSpeed(pxPerSec: Float) {
        _speedPxPerSec.value = pxPerSec.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    // ─── The actual scroll loop ────────────────────────────────────

    /**
     * The teleprompter loop. Runs until either:
     *   - we reach the end of the list (→ CHAPTER_END, fire callback)
     *   - the coroutine is cancelled (pause/stop/scope-cancel)
     *
     * Frame timing notes:
     *  - withFrameNanos resolves with a System.nanoTime()-like value
     *    on the next vsync. Order-of-magnitude: 16.67ms apart on 60Hz.
     *  - The first frame's deltaT is forced to 0 to avoid a giant
     *    initial scroll if we've been suspended for a long time
     *    (e.g. process was paged out).
     *  - We accumulate fractional pixels so slow speeds (e.g. 20 px/s
     *    @ 120Hz = 0.166px/frame) don't lose precision to integer
     *    rounding. scrollBy takes Float, so we pass the accumulator
     *    directly.
     */
    private suspend fun runScrollLoop() {
        try {
            var lastFrameNs = 0L
            var pixelAccumulator = 0f

            while (true) {
                val frameNs = withFrameNanos { it }
                val deltaSeconds = if (lastFrameNs == 0L) {
                    0f  // Skip the first frame's massive delta
                } else {
                    (frameNs - lastFrameNs) / 1_000_000_000f
                }
                lastFrameNs = frameNs

                // How far should we scroll this frame?
                pixelAccumulator += deltaSeconds * _speedPxPerSec.value

                // Skip the scroll call if we've accumulated less than
                // a full pixel. Avoids hammering the scroll API at
                // sub-pixel deltas which it'll round to zero anyway.
                if (pixelAccumulator < 1f) continue

                // Snapshot the scroll request, then reset the
                // accumulator immediately. If scrollBy throws or is
                // cancelled mid-call, we don't want to double-count
                // those pixels on the next frame.
                val scrollRequest = pixelAccumulator
                pixelAccumulator = 0f

                val consumed = listState.driftBy(scrollRequest)

                // End-of-content detection. If scrollBy consumed less
                // than we asked for, the list is at its bottom edge.
                // This is more reliable than polling
                // `lastVisibleItemIndex == itemCount - 1` because
                // the very last item might be partially visible.
                //
                // Tolerance: we say "stuck at bottom" only if we
                // consumed less than 50% of what we asked for. Some
                // frames consume slightly less due to deceleration
                // edges in scroll physics; we don't want to false-
                // positive on those.
                if (consumed < scrollRequest * 0.5f) {
                    _state.value = State.CHAPTER_END
                    chapterEndCallback?.invoke()
                    return
                }
            }
        } catch (e: CancellationException) {
            // Normal — pause()/stop() cancels the job. Don't log.
            throw e
        } catch (e: Exception) {
            // Anything else is a bug. Log and stop cleanly so the
            // user isn't left with a frozen scroll mode.
            Logger.e(TAG, "Scroll loop failed", e)
            _state.value = State.IDLE
        }
    }

    /**
     * Scroll the LazyList by [pixels] and report how many pixels were
     * actually consumed. Consumed < requested means we hit a content
     * edge — used for end-of-chapter detection.
     *
     * Named `driftBy` rather than `scrollBy` to avoid a name collision
     * with `ScrollScope.scrollBy` (the member that's available inside
     * the `scroll { ... }` lambda below). Without the rename, the
     * inner `scrollBy(pixels)` call would be ambiguous between the
     * ScrollScope member and this extension, and on some Compose
     * versions resolves to recursion → stack overflow.
     */
    private suspend fun LazyListState.driftBy(pixels: Float): Float {
        var consumed = 0f
        scroll {
            // Inside `scroll { }` we're in ScrollScope, so this call
            // resolves to ScrollScope.scrollBy(Float): Float — the
            // primitive that returns consumed pixels.
            consumed = scrollBy(pixels)
        }
        return consumed
    }

    companion object {
        private const val TAG = "AutoScrollController"

        /** Min/max speeds in px/sec. */
        const val MIN_SPEED = 20f
        const val MAX_SPEED = 200f
        const val DEFAULT_SPEED = 60f

        /** Pause-at-top duration after auto-advancing chapters. */
        private const val CHAPTER_RESUME_DELAY_MS = 2_000L
    }
}