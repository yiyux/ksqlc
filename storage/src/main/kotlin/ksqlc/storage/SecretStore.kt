package ksqlc.storage

import com.github.javakeyring.Keyring

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
