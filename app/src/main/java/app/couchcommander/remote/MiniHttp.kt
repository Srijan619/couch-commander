package app.couchcommander.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** One request from the phone: small JSON bodies only. */
data class Request(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    /** The pairing token from `Authorization: Bearer <token>`. */
    val bearer: String? get() = headers["authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
}

data class Response(val status: Int, val body: String = "{}") {
    companion object {
        fun error(status: Int, message: String) = Response(status, """{"error":${quote(message)}}""")
        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/**
 * Just enough HTTP/1.1 for the phone's requests: one request per connection, a Content-Length body, JSON answers.
 * A library server would be most of the app's size for four endpoints.
 */
object MiniHttp {
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = 64 * 1024

    /** Null when the connection closed or sent something that isn't a request this can answer. */
    fun read(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        return Request(
            method = parts[0].uppercase(),
            path = decode(if (q >= 0) target.substring(0, q) else target),
            query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap(),
            headers = headers,
            body = body.toString(Charsets.UTF_8),
        )
    }

    fun write(out: OutputStream, response: Response) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    fun parseQuery(text: String): Map<String, String> =
        text.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) decode(it) to "" else decode(it.substring(0, eq)) to decode(it.substring(eq + 1))
        }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || buf.size() >= MAX_HEAD) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
        }
        return buf.toString(Charsets.US_ASCII.name()).dropLast(4)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}
