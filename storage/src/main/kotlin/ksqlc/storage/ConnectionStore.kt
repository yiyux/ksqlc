package ksqlc.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksqlc.core.DataSource
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
private data class Stored(val version: Int = 1, val connections: List<DataSource> = emptyList())

/**
 * Connections as JSON in [dir]/connections.json; passwords only in [secrets].
 * A blank password clears any stored secret.
 */
class ConnectionStore(val dir: Path, @Volatile private var secrets: SecretStore) {
    private val file = dir.resolve("connections.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun list(): List<DataSource> =
        if (!file.exists()) emptyList()
        else json.decodeFromString<Stored>(file.readText()).connections

    fun password(id: String): String? = secrets.get(id)

    /** The SSH tunnel's password or key passphrase. */
    fun sshSecret(id: String): String? = secrets.get(sshKey(id))

    private fun sshKey(id: String) = "$id#ssh"

    /** Inserts or replaces by id. Keychain first, so a failure leaves the file untouched. */
    fun save(ds: DataSource, password: String?, sshSecret: String? = ds.sshSecret) {
        if (password.isNullOrEmpty()) secrets.delete(ds.id) else secrets.set(ds.id, password)
        if (sshSecret.isNullOrEmpty()) secrets.delete(sshKey(ds.id)) else secrets.set(sshKey(ds.id), sshSecret)
        write(list().filterNot { it.id == ds.id } + ds.copy(sshSecret = null))
    }

    fun delete(id: String) {
        write(list().filterNot { it.id == id })
        secrets.delete(id)
        secrets.delete(sshKey(id))
    }

    /** Moves every stored secret to [to], copying before deleting so a failure leaves the old store whole. */
    fun switchSecrets(to: SecretStore) {
        val from = secrets
        if (from === to) return
        val keys = list().flatMap { listOf(it.id, sshKey(it.id)) }
        val moved = keys.mapNotNull { k -> from.get(k)?.let { k to it } }
        moved.forEach { (k, v) -> to.set(k, v) }
        secrets = to
        moved.forEach { (k, _) -> runCatching { from.delete(k) } }
    }

    private fun write(all: List<DataSource>) {
        atomicWrite(file, json.encodeToString(Stored(connections = all.sortedBy { it.name.lowercase() })))
    }

    companion object {
        fun default(): ConnectionStore =
            ConnectionStore(Path.of(System.getProperty("user.home"), ".ksqlc"), KeyringSecretStore())
    }
}
