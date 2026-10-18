package app.couchcommander.remote

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A phone asking to pair: the code the TV shows, which the phone sends back. */
data class PairRequest(val code: String, val name: String, val expiresAt: Instant, val attempts: Int = 0)

/**
 * Pairing a phone: it asks, the TV shows a 4-digit code, the phone sends the code back and gets a token it
 * sends with every request after. One request at a time; it ends after [TTL] or [MAX_ATTEMPTS] wrong codes.
 */
class Pairing(private val tokens: RemoteTokens, private val now: () -> Instant = Instant::now) {
    private val random = SecureRandom()
    private val _request = MutableStateFlow<PairRequest?>(null)
    /** What the TV shows; null when no phone is pairing. */
    val request: StateFlow<PairRequest?> = _request.asStateFlow()

    /** Starts pairing [name]; false while another phone is pairing. */
    @Synchronized
    fun start(name: String): Boolean {
        val current = live()
        if (current != null && current.name != name) return false
        _request.value = PairRequest("%04d".format(random.nextInt(10_000)), name.take(60), now().plus(TTL))
        return true
    }

    /** The new token when [code] is right; null otherwise. */
    @Synchronized
    fun confirm(code: String): String? {
        val current = live() ?: return null
        if (!MessageDigest.isEqual(code.trim().toByteArray(), current.code.toByteArray())) {
            val tried = current.copy(attempts = current.attempts + 1)
            _request.value = if (tried.attempts >= MAX_ATTEMPTS) null else tried
            return null
        }
        _request.value = null
        return tokens.add(current.name)
    }

    /** Back on the TV's code screen. */
    fun cancel() { _request.value = null }

    private fun live(): PairRequest? = _request.value?.takeIf { it.expiresAt.isAfter(now()) } ?: run { _request.value = null; null }

    companion object {
        val TTL: Duration = Duration.ofMinutes(2)
        const val MAX_ATTEMPTS = 5
    }
}

/** The paired phones; only a hash of each token is kept, so the file holds nothing worth stealing. */
class RemoteTokens(dir: File) {
    @Serializable
    data class Paired(val hash: String, val name: String, val atEpochMillis: Long)

    @Serializable
    private class TokensFile(val paired: List<Paired>)

    private val file = File(dir, "remote-paired.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    @Volatile private var paired: List<Paired> = runCatching { json.decodeFromString(TokensFile.serializer(), file.readText()).paired }.getOrDefault(emptyList())

    val all: List<Paired> get() = paired

    fun valid(token: String?): Boolean = token != null && hash(token).let { h -> paired.any { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) } }

    /** A new token for [name]; pairing the same name again replaces its old one. */
    @Synchronized
    fun add(name: String): String {
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        save(paired.filterNot { it.name == name } + Paired(hash(token), name, System.currentTimeMillis()))
        return token
    }

    @Synchronized
    fun forgetAll() = save(emptyList())

    private fun save(next: List<Paired>) {
        paired = next
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(TokensFile.serializer(), TokensFile(next)))
            tmp.renameTo(file)
        }
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
