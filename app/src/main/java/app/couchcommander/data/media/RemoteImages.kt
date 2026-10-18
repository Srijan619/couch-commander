package app.couchcommander.data.media

import android.graphics.BitmapFactory
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
 * Posters and backdrops from TMDB's image CDN for the Movies and TV pages: downloaded once into the cache dir,
 * decoded no wider than asked, and kept in memory up to [MAX_MEMORY_BYTES]. One that fails isn't asked for again
 * until the app restarts. Never throws: no image means the tile shows the title instead.
 */
class RemoteImages(cacheDir: File) {
    private val dir = File(cacheDir, "media-images")
    private val memory = object : LruCache<String, ImageBitmap>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    /** A shelf asks for a dozen posters at once; the CDN copes, the TV's Wi-Fi less so. */
    private val downloads = Semaphore(4)

    fun cached(url: String, widthPx: Int): ImageBitmap? = memory.get(key(url, widthPx))

    suspend fun load(url: String, widthPx: Int): ImageBitmap? {
        val key = key(url, widthPx)
        memory.get(key)?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            val file = File(dir, sha(url))
            val image = runCatching {
                if (!file.exists()) downloads.withPermit { download(url, file) }
                decode(file, widthPx)
            }.getOrNull()
            if (image == null) { failed += url; file.delete() } else memory.put(key, image)
            image
        }
    }

    private fun key(url: String, widthPx: Int) = "$widthPx|$url"

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
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
                        check(total <= MAX_BYTES) { "Image too big" }
                        out.write(buffer, 0, n)
                    }
                }
            }
            tmp.renameTo(target)
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(file: File, widthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private companion object {
        /** About 60 posters and a few backdrops. */
        const val MAX_MEMORY_BYTES = 24 * 1024 * 1024
        const val MAX_BYTES = 2 * 1024 * 1024L
    }
}
