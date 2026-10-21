package ksqlc.storage

import com.github.javakeyring.Keyring
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Where connection passwords live. Never written to the connections file. */
interface SecretStore {
    fun get(id: String): String?
    fun set(id: String, secret: String)
    fun delete(id: String)
}

/** OS keychain (macOS Keychain, Windows Credential Manager, libsecret) via java-keyring. */
class KeyringSecretStore(private val service: String = "ksqlc") : SecretStore {
    private val keyring: Keyring by lazy { Keyring.create() }

    override fun get(id: String): String? =
        runCatching { keyring.getPassword(service, id) }.getOrNull()

    override fun set(id: String, secret: String) = keyring.setPassword(service, id, secret)

    override fun delete(id: String) {
        runCatching { keyring.deletePassword(service, id) }
    }
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun get(id: String) = map[id]
    override fun set(id: String, secret: String) { map[id] = secret }
    override fun delete(id: String) { map.remove(id) }
}

@Serializable
private class Vault(val v: Int = 1, val salt: String, val iv: String, val data: String)

/**
 * Secrets in one file encrypted with AES-256-GCM under a key derived from [master] (PBKDF2-HMAC-SHA256). A wrong master
 * password fails the GCM check on open. The key is derived once; every write uses a fresh IV.
 */
class EncryptedFileSecretStore(private val file: Path, master: CharArray) : SecretStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder()
    private val rnd = SecureRandom()
    private val salt: ByteArray
    private val key: SecretKeySpec
    private val map = mutableMapOf<String, String>()

    init {
        val old = if (file.exists()) json.decodeFromString<Vault>(file.readText()) else null
        salt = old?.let { Base64.getDecoder().decode(it.salt) } ?: ByteArray(16).also(rnd::nextBytes)
        val spec = PBEKeySpec(master, salt, ITERATIONS, 256)
        key = try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
        if (old != null) {
            val plain = try { cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(old.iv)).doFinal(Base64.getDecoder().decode(old.data)) }
            catch (e: AEADBadTagException) { throw IllegalArgumentException("Contraseña maestra incorrecta") }
            map += json.decodeFromString<Map<String, String>>(String(plain, Charsets.UTF_8))
        } else flush()   // an unwritable path or an empty master shows up now, not on the first save
    }

    private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, iv)) }

    private fun flush() {
        val iv = ByteArray(12).also(rnd::nextBytes)
        val data = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(json.encodeToString(map.toMap()).toByteArray(Charsets.UTF_8))
        atomicWrite(file, json.encodeToString(Vault(salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(data))))
    }

    @Synchronized override fun get(id: String) = map[id]
    @Synchronized override fun set(id: String, secret: String) { map[id] = secret; flush() }
    @Synchronized override fun delete(id: String) { if (map.remove(id) != null) flush() }

    private companion object { const val ITERATIONS = 600_000 }
}

/** The store [s] asks for. [master] is only used for [PasswordStorage.FILE]. */
fun secretStoreFor(s: Settings, dir: Path, master: CharArray): SecretStore = when (s.passwordStorage) {
    PasswordStorage.KEYCHAIN -> KeyringSecretStore()
    PasswordStorage.FILE -> EncryptedFileSecretStore(if (s.passwordFile.isBlank()) dir.resolve("secrets.enc") else Path.of(s.passwordFile), master)
    PasswordStorage.NONE -> InMemorySecretStore()
}
