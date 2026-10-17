package app.couchcommander.data.iptv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Channel logos from the playlist (`tvg-logo`, Xtream's `stream_icon`) for the football page's channel tiles.
 * Downloaded once into the cache dir, decoded small, and kept in memory; a logo that fails isn't asked for again
 * until the app restarts. Never throws: no logo means the tile shows the channel's initials.
 */
class ChannelLogos(cacheDir: File) {
    /** [light]: the logo is drawn in light colours (white text for dark grounds), so it needs a dark square. */
    class Logo(val image: ImageBitmap, val light: Boolean)

    private val dir = File(cacheDir, "logos")
    private val memory = LruCache<String, Logo>(MAX_IN_MEMORY)
    private val failed = ConcurrentHashMap.newKeySet<String>()
    /** A row of tiles asks for a handful at once; the provider's logo host needn't see them all together. */
    private val downloads = Semaphore(4)

    fun cached(url: String): Logo? = memory.get(url)

    suspend fun load(url: String): Logo? {
        memory.get(url)?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            val file = File(dir, sha(url))
            val image = runCatching {
                if (!file.exists()) downloads.withPermit { download(url, file) }
                decode(file)
            }.getOrNull()
            if (image == null) { failed += url; file.delete() } else memory.put(url, image)
            image
        }
    }

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            dir.mkdirs()
            val tmp = File(dir, "${target.name}.tmp")
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_BYTES) { "Logo too big" }
                        out.write(buffer, 0, n)
                    }
                }
            }
            tmp.renameTo(target)
        } finally {
            connection.disconnect()
        }
    }

    /** Decoded at a fraction of its size: the tile draws it at about 33 dp. */
    private fun decode(file: File): Logo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return Logo(bitmap.asImageBitmap(), isLight(bitmap))
    }

    /**
     * True when the logo has white lettering on a transparent background, so it needs a dark square. Counted as a
     * share, not an average: TNT's white "SPORTS 2" beside a bright pink disc averages out mid-grey and would vanish on
     * white. A logo with its own solid background brings its contrast along, so it keeps the light square.
     */
    private fun isLight(bitmap: Bitmap): Boolean {
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 32)
        var samples = 0
        var visible = 0
        var light = 0
        var dark = 0
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            samples++
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            visible++
            val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
            if (lum > LIGHT) light++ else if (lum < DARK) dark++
        }
        val transparent = samples - visible
        if (visible == 0 || transparent < samples * MIN_TRANSPARENT) return false
        return light > visible * MIN_LIGHT_SHARE && light > dark
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private companion object {
        const val MAX_IN_MEMORY = 200
        const val MAX_BYTES = 512 * 1024L
        const val TARGET_PX = 96
        /** Near-white and near-black, by luminance. */
        const val LIGHT = 0.8
        const val DARK = 0.35
        /** Below this share of transparent pixels the logo has its own background. */
        const val MIN_TRANSPARENT = 0.1
        const val MIN_LIGHT_SHARE = 0.2
    }
}
