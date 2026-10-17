package app.couchcommander.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.couchcommander.R
import app.couchcommander.domain.Accent
import app.couchcommander.domain.ArtSchedule
import app.couchcommander.domain.LauncherConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CouchArt"
private const val USER_AGENT = "CouchCommander/0.1 (personal Android TV launcher)"

/** A painting ready to draw. [framed] paintings are too square to crop to 16:9, so they hang on a coloured wall. */
class ArtFrame(val artwork: Artwork, val image: ImageBitmap, val accent: Int, val framed: Boolean)

/**
 * Follows [ArtSchedule]: shows the painting for the current slot, downloads it on first use and keeps it in the cache
 * directory, and prefetches the next one. If a download fails, the next cached painting is shown instead, and the
 * bundled one if nothing is cached yet. Only one painting is fetched per slot, to stay polite to Wikimedia.
 */
class ArtRepository(private val context: Context, private val config: StateFlow<LauncherConfig>, scope: CoroutineScope) {
    private val dir = File(context.cacheDir, "art")
    private val schedule = ArtSchedule(Artworks.all.size)
    private val skip = MutableStateFlow(0L)

    private val _frame = MutableStateFlow<ArtFrame?>(null)
    val frame: StateFlow<ArtFrame?> = _frame.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            _frame.value = runCatching { fallback() }.onFailure { Log.w(TAG, "no fallback art", it) }.getOrNull()
            // Colour boost is baked into the image, so toggling it reloads the painting from the cache.
            combine(config.map { it.artMinutes }.distinctUntilChanged(), config.map { it.colourBoost }.distinctUntilChanged(), skip) { minutes, _, s -> minutes to s }
                .collectLatest { (minutes, s) ->
                    val period = minutes.coerceAtLeast(1) * 60_000L
                    while (true) {
                        val now = System.currentTimeMillis()
                        val slot = schedule.slot(now, period)
                        show(slot, s)
                        prefetch(Artworks.all[schedule.indexAt(slot + 1, s)])
                        delay((schedule.nextChange(now, period) - System.currentTimeMillis()).coerceAtLeast(1_000L))
                    }
                }
        }
    }

    /** Skips to the next painting in the order. */
    fun next() = skip.update { it + 1 }

    private suspend fun show(slot: Long, skip: Long) {
        val wanted = Artworks.all[schedule.indexAt(slot, skip)]
        val frame = load(wanted, download = true) ?: firstCachedAfter(slot, skip)
        if (frame != null) _frame.value = frame
    }

    private suspend fun prefetch(artwork: Artwork) = withContext(Dispatchers.IO) {
        val file = cacheFile(artwork)
        if (!file.exists()) runCatching { download(artwork, file) }.onFailure { Log.i(TAG, "prefetch failed: ${artwork.file}", it) }
    }

    private suspend fun firstCachedAfter(slot: Long, skip: Long): ArtFrame? {
        for (i in 1 until Artworks.all.size) {
            load(Artworks.all[schedule.indexAt(slot + i, skip)], download = false)?.let { return it }
        }
        return null
    }

    private suspend fun load(artwork: Artwork, download: Boolean): ArtFrame? = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(artwork)
            if (!file.exists()) {
                if (!download) return@runCatching null
                download(artwork, file)
            }
            val bitmap = BitmapFactory.decodeFile(file.path) ?: run {
                file.delete()
                error("undecodable image")
            }
            frameOf(artwork, bitmap)
        }.onFailure { Log.w(TAG, "couldn't load ${artwork.file}", it) }.getOrNull()
    }

    private fun download(artwork: Artwork, target: File) {
        dir.mkdirs()
        val connection = URL(Artworks.url(artwork)).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            check(connection.contentType?.startsWith("image/") == true) { "not an image: ${connection.contentType}" }
            val tmp = File(dir, "${target.name}.tmp")
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "couldn't move ${tmp.name}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun fallback(): ArtFrame =
        frameOf(Artworks.fallback, BitmapFactory.decodeResource(context.resources, R.drawable.fallback_art))

    private fun frameOf(artwork: Artwork, bitmap: Bitmap): ArtFrame {
        val w = 48
        val h = (48f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== bitmap) small.recycle()
        val accent = Accent.vibrant(pixels)
        val framed = bitmap.width.toFloat() / bitmap.height < FRAMED_BELOW_ASPECT
        val baked = bake(bitmap, accent, tint = !framed && config.value.colourBoost)
        return ArtFrame(artwork = artwork, image = baked.asImageBitmap(), accent = accent, framed = framed)
    }

    /**
     * Applies the saturation and the colour-boost tint once, here, instead of on every frame: the TV's GPU then only
     * has to draw a plain bitmap behind the tiles, which keeps navigation smooth.
     */
    private fun bake(source: Bitmap, accent: Int, tint: Boolean): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val saturate = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = saturate })
        if (tint) {
            val paint = Paint().apply { color = accent }
            // Soft light pushes the painting's own colour; older Androids only get a light wash.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                paint.alpha = 90
                paint.blendMode = BlendMode.SOFT_LIGHT
            } else {
                paint.alpha = 26
            }
            canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        }
        source.recycle()
        // Uploads the texture now, on this thread, rather than in the middle of the crossfade.
        out.prepareToDraw()
        return out
    }

    private fun cacheFile(artwork: Artwork): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(artwork.file.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    private companion object {
        /** Wider than this is cropped to fill the screen; narrower is shown whole, framed. */
        const val FRAMED_BELOW_ASPECT = 1.2f
        const val SATURATION = 1.3f
    }
}
