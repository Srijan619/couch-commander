package app.couchcommander.data.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.media.trakt.TraktTokenStore
import app.media.trakt.TraktTokens
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
 * Trakt tokens in a file, sealed with an Android Keystore key that never leaves the TV.
 * If decryption fails (e.g. key wiped, restored elsewhere), the device is simply signed out.
 */
class KeystoreTraktTokenStore(dir: File) : TraktTokenStore {
    private val file = File(dir, "trakt-tokens.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Saved(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val username: String = "",
    )

    override suspend fun load(): TraktTokens? = withContext(Dispatchers.IO) {
        val saved = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull() ?: return@withContext null
        val accessToken = runCatching { decrypt(saved.accessToken) }.getOrNull()
        val refreshToken = runCatching { decrypt(saved.refreshToken) }.getOrNull()
        if (accessToken == null || refreshToken == null) {
            file.delete()
            return@withContext null
        }
        TraktTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = saved.expiresAtMillis,
            username = saved.username,
        )
    }

    override suspend fun save(tokens: TraktTokens?) = withContext(Dispatchers.IO) {
        if (tokens == null) {
            file.delete()
            return@withContext
        }
        val saved = Saved(
            accessToken = encrypt(tokens.accessToken),
            refreshToken = encrypt(tokens.refreshToken),
            expiresAtMillis = tokens.expiresAtMillis,
            username = tokens.username,
        )
        val text = json.encodeToString(Saved.serializer(), saved)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
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
        const val KEY_ALIAS = "trakt_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
