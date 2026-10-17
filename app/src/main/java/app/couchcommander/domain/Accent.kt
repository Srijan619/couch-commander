package app.couchcommander.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Picks the most vivid colour family in a painting, for the tint, the focus glow and the screensaver wall.
 * A small stand-in for androidx.palette's "vibrant" swatch, so the app needs no extra library.
 */
object Accent {
    const val FALLBACK = 0xFFFF5A36.toInt()

    private const val HUE_BINS = 24

    /** [argb] is a downscaled image (a few thousand pixels is plenty). Returns an opaque ARGB colour. */
    fun vibrant(argb: IntArray): Int {
        val score = DoubleArray(HUE_BINS)
        val sumR = DoubleArray(HUE_BINS)
        val sumG = DoubleArray(HUE_BINS)
        val sumB = DoubleArray(HUE_BINS)
        val hsv = FloatArray(3)
        for (p in argb) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            toHsv(r, g, b, hsv)
            // Greys, near-blacks and washed-out pixels say nothing about the painting's colour.
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            val w = (hsv[1] * hsv[2]).toDouble()
            score[bin] += w
            sumR[bin] += r * w; sumG[bin] += g * w; sumB[bin] += b * w
        }
        val best = score.indices.maxByOrNull { score[it] } ?: return FALLBACK
        if (score[best] == 0.0) return FALLBACK
        val avg = rgb((sumR[best] / score[best]).toInt(), (sumG[best] / score[best]).toInt(), (sumB[best] / score[best]).toInt())
        // Push it towards a saturated, bright version: this colour is used as light, not as a sample.
        toHsv((avg shr 16) and 0xFF, (avg shr 8) and 0xFF, avg and 0xFF, hsv)
        return fromHsv(hsv[0], max(hsv[1], 0.7f), max(hsv[2], 0.9f))
    }

    /** A deep version of [accent], for the wall behind framed paintings. */
    fun wall(accent: Int): Int {
        val hsv = FloatArray(3)
        toHsv((accent shr 16) and 0xFF, (accent shr 8) and 0xFF, accent and 0xFF, hsv)
        return fromHsv(hsv[0], min(hsv[1], 0.75f), 0.3f)
    }

    internal fun toHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d).mod(6f))
            mx == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    internal fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f).mod(2f) - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
