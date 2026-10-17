package app.couchcommander.data.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The playlist source, encrypted with a key that never leaves the Android Keystore. Kept apart from the
 * settings file, and excluded from backups (the key isn't backed up, so a restored copy couldn't be read anyway).
 */
class IptvSourceStore(dir: File) {
    private val file = File(dir, "iptv-source.bin")
    private val json = Json { ignoreUnknownKeys = true }

    private val _source = MutableStateFlow(runCatching { load() }.getOrNull())
    val source: StateFlow<IptvSource?> = _source.asStateFlow()

    /** Null forgets the source; the repository then drops its channels too. */
    @Synchronized
    fun set(source: IptvSource?) {
        if (source == null) file.delete()
        else {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.iv + cipher.doFinal(json.encodeToString(IptvSource.serializer(), source).toByteArray())
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        _source.value = source
    }

    private fun load(): IptvSource? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE)) }
        return json.decodeFromString(IptvSource.serializer(), String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "iptv-source"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
