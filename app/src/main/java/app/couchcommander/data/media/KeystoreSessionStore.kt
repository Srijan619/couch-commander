package app.couchcommander.data.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.media.TmdbSession
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The signed-in TMDB session in a file, its id encrypted with an Android Keystore key that never leaves the TV
 * (garbled's TmdbSessionStore), so a backup or a copied data folder doesn't carry a usable session. If it can't be
 * decrypted (key wiped, restored elsewhere), the launcher is simply signed out.
 */
class KeystoreSessionStore(dir: File) : SessionStore {
    private val file = File(dir, "tmdb-session.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Saved(val session: String, val accountId: Int, val username: String)

    override suspend fun load(): TmdbSession? = withContext(Dispatchers.IO) {
        val saved = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull() ?: return@withContext null
        val sessionId = runCatching { decrypt(saved.session) }.getOrNull()
        if (sessionId == null) {
            file.delete()
            return@withContext null
        }
        TmdbSession(sessionId, saved.accountId, saved.username)
    }

    override suspend fun save(session: TmdbSession) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(Saved.serializer(), Saved(encrypt(session.sessionId), session.accountId, session.username))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tmdb_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
