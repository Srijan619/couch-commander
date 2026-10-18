package app.couchcommander.ui.page

import android.graphics.Bitmap

/**
 * A cheap, one-off blur for a full-screen backdrop: shrink the painting to a thumbnail, box-blur that a few
 * times (close to a Gaussian), and let the GPU stretch it back up with bilinear filtering. Costs a few
 * milliseconds once per painting, against a live RenderEffect blur that the TV redid on every frame.
 */
object Blur {
    private const val WIDTH = 160
    private const val RADIUS = 4
    private const val PASSES = 3

    /** [dim] blends towards [ground] (0..1), baked in so the page needs no full-screen translucent layer on top. */
    fun small(source: Bitmap, dim: Float = 0f, ground: Int = 0): Bitmap {
        val w = WIDTH.coerceAtMost(source.width)
        val h = (source.height * w.toFloat() / source.width).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(source.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
        val pixels = IntArray(w * h).also { thumb.getPixels(it, 0, w, 0, 0, w, h) }
        val tmp = IntArray(pixels.size)
        repeat(PASSES) {
            box(pixels, tmp, w, h, horizontal = true)
            box(tmp, pixels, w, h, horizontal = false)
        }
        if (dim > 0f) {
            val keep = 1f - dim
            val gr = (ground shr 16 and 0xff) * dim; val gg = (ground shr 8 and 0xff) * dim; val gb = (ground and 0xff) * dim
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (0xff shl 24) or (((p shr 16 and 0xff) * keep + gr).toInt() shl 16) or (((p shr 8 and 0xff) * keep + gg).toInt() shl 8) or ((p and 0xff) * keep + gb).toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One running-sum box blur along rows or columns, edges clamped. Alpha is dropped: paintings are opaque. */
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val window = RADIUS * 2 + 1
        for (line in 0 until lines) {
            fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
            var r = 0; var g = 0; var b = 0
            for (i in -RADIUS..RADIUS) at(i).let { r += it shr 16 and 0xff; g += it shr 8 and 0xff; b += it and 0xff }
            for (i in 0 until len) {
                dst[if (horizontal) line * w + i else i * w + line] = (0xff shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = at(i - RADIUS)
                val inn = at(i + RADIUS + 1)
                r += (inn shr 16 and 0xff) - (out shr 16 and 0xff)
                g += (inn shr 8 and 0xff) - (out shr 8 and 0xff)
                b += (inn and 0xff) - (out and 0xff)
            }
        }
    }
}
