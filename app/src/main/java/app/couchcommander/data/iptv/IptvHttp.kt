package app.couchcommander.data.iptv

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.zip.GZIPInputStream

/** Opens a URL as a stream for one [read]; throws [IOException] on failure. Messages never contain the URL (it holds the login). */
interface IptvFetch {
    fun <T> get(url: String, maxBytes: Long, read: (InputStream) -> T): T
}

/**
 * Plain blocking GET for the sync thread: follows redirects across http/https (HttpURLConnection won't),
 * un-gzips `.gz` guides, and stops a download that grows past its size cap or the overall [deadline].
 */
class IptvHttp(
    private val userAgent: String = USER_AGENT,
    private val deadline: Duration = Duration.ofMinutes(5),
) : IptvFetch {
    override fun <T> get(url: String, maxBytes: Long, read: (InputStream) -> T): T {
        val until = System.nanoTime() + deadline.toNanos()
        var target = URL(url)
        repeat(MAX_REDIRECTS) {
            val c = target.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", userAgent)
                when (val code = c.responseCode) {
                    in 300..399 -> {
                        target = URL(target, c.getHeaderField("Location") ?: throw IOException("Redirect without a location"))
                        return@repeat
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val input = BufferedInputStream(Capped(c.inputStream, maxBytes, until), BUFFER)
                        return read(ungzip(input))
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /** Guides are often served as `.xml.gz` without a gzip Content-Encoding; the magic bytes tell. */
    private fun ungzip(input: BufferedInputStream): InputStream {
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gzip) BufferedInputStream(GZIPInputStream(input, BUFFER), BUFFER) else input
    }

    private class Capped(input: InputStream, private val max: Long, private val until: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            count += n
            if (count > max) throw IOException("Download is larger than ${max / 1_000_000} MB")
            if (System.nanoTime() > until) throw IOException("Download took too long")
        }
    }

    companion object {
        /** Some panels refuse Java's and ExoPlayer's default agents; a desktop browser's gets through. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MAX_REDIRECTS = 6
        private const val BUFFER = 64 * 1024
    }
}
