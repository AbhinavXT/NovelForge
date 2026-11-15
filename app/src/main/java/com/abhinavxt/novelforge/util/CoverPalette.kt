package com.abhinavxt.novelforge.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Picks one accent colour from a book cover, and adapts it for use on a
 * given surface. Both steps are pure (ARGB ints in, ARGB ints out) so they
 * can be unit tested without Android or Compose.
 */
object CoverPalette {

    private const val HUE_BUCKETS = 24
    private const val MIN_SATURATION = 0.28f
    private const val MIN_VALUE = 0.22f

    // A cover with less than this share of vivid pixels is treated as
    // greyscale/blank; a few stray coloured pixels shouldn't tint the page.
    private const val MIN_VIVID_SHARE = 0.03f

    /**
     * The cover's most representative vivid colour, or null when the cover
     * is essentially greyscale or blank. Near-white, near-black and
     * low-saturation pixels are dropped, the rest are bucketed by hue and
     * weighted by saturation; the heaviest bucket's weighted average wins.
     */
    fun dominantColor(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = FloatArray(HUE_BUCKETS)
        val sumR = FloatArray(HUE_BUCKETS)
        val sumG = FloatArray(HUE_BUCKETS)
        val sumB = FloatArray(HUE_BUCKETS)
        var vivid = 0
        val hsv = FloatArray(3)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            rgbToHsv(r, g, b, hsv)
            val s = hsv[1]
            val v = hsv[2]
            // Near-white: bright with little colour. Near-black: too dark to carry a hue.
            if (s < MIN_SATURATION || v < MIN_VALUE) continue
            vivid++
            val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[bucket] += s
            sumR[bucket] += r * s
            sumG[bucket] += g * s
            sumB[bucket] += b * s
        }
        if (vivid.toFloat() / pixels.size < MIN_VIVID_SHARE) return null
        var best = 0
        for (i in 1 until HUE_BUCKETS) if (weight[i] > weight[best]) best = i
        if (weight[best] <= 0f) return null
        return argb(
            Math.round(sumR[best] / weight[best]),
            Math.round(sumG[best] / weight[best]),
            Math.round(sumB[best] / weight[best])
        )
    }

    /**
     * Shifts [accent] lighter or darker, keeping its hue, until it reaches
     * [minContrast] against [surface] so it stays legible as text or an
     * icon. Goes whichever way the surface isn't: lighter on dark surfaces,
     * darker on light ones.
     */
    fun accentOn(accent: Int, surface: Int, minContrast: Double = 4.5): Int {
        if (contrast(accent, surface) >= minContrast) return accent
        val target = if (luminance(surface) < 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        // A mid-grey surface can't reach the ratio either way; take the extreme.
        if (contrast(target, surface) < minContrast) return target
        var lo = 0f
        var hi = 1f
        // Contrast only grows toward the target, so bisect for the smallest shift.
        repeat(12) {
            val mid = (lo + hi) / 2
            if (contrast(blend(accent, target, mid), surface) >= minContrast) hi = mid else lo = mid
        }
        return blend(accent, target, hi)
    }

    /** Black or white, whichever reads better on a filled [accent] button. */
    fun onAccent(accent: Int): Int {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(accent, black) >= contrast(accent, white)) black else white
    }

    fun blend(from: Int, to: Int, t: Float): Int = argb(
        lerp((from shr 16) and 0xFF, (to shr 16) and 0xFF, t),
        lerp((from shr 8) and 0xFF, (to shr 8) and 0xFF, t),
        lerp(from and 0xFF, to and 0xFF, t)
    )

    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    // android.graphics.Color is a stub in JVM unit tests, so do the maths here.
    private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        out[0] = if (h < 0f) h + 360f else h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }
}

/**
 * Loads a cover through the app's Coil loader at a tiny size and extracts
 * its accent. Results (including "no accent") are cached by cover URL so
 * reopening a book shows its colour immediately.
 */
object CoverAccent {

    private const val SAMPLE_W = 48
    private const val SAMPLE_H = 72

    // LruCache can't hold null, so "no accent" is stored as this marker.
    private const val NONE = 0
    private val cache = LruCache<String, Int>(50)

    /** The cover's accent; [color] is null when it has none. */
    class Result(val color: Int?)

    /** The cached answer without loading, or null when this cover hasn't been looked at yet. */
    fun cached(coverUrl: String): Result? {
        val hit = cache.get(coverUrl) ?: return null
        return Result(if (hit == NONE) null else hit)
    }

    suspend fun load(context: Context, coverUrl: String): Result = withContext(Dispatchers.IO) {
        cached(coverUrl)?.let { return@withContext it }
        val data: Any = if (coverUrl.startsWith("/")) File(coverUrl) else coverUrl
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(Size(SAMPLE_W, SAMPLE_H))
            .precision(Precision.INEXACT)
            // Hardware bitmaps can't be read back with getPixels.
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
            // A failed load isn't cached, so a later visit can retry.
            ?: return@withContext Result(null)
        val color = dominantColor(bitmap)
        cache.put(coverUrl, color ?: NONE)
        Result(color)
    }

    private fun dominantColor(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return CoverPalette.dominantColor(pixels)
    }
}
