package ksqlc.core

import kotlinx.serialization.Serializable

@Serializable
enum class DbKind { POSTGRES, MYSQL, MARIADB, SQLITE, MSSQL, ORACLE, CASSANDRA }

/** The "Options" of a data source. Zero or blank means off; every field keeps its default so older saved connections load unchanged. */
@Serializable
data class ConnectionOptions(
    /** Only reads are allowed on this connection (the JDBC flag where the driver honours it, and a check on what is sent). */
    val readOnly: Boolean = false,
    /** Session time zone (`Europe/Madrid`, `+01:00`) for the engines that have one; blank = the server's. */
    val timeZone: String = "",
    /** Run a trivial query this often while the console connection is idle, so firewalls do not cut it. */
    val keepAliveSeconds: Int = 0,
    /** Close the console connection after this long without use. */
    val autoDisconnectSeconds: Int = 0,
    /** List only the database (keyspace, schema) the connection names, instead of every one the server has. */
    val singleDatabase: Boolean = false,
    /** SQL run each time a connection opens. */
    val startupScript: String = "",
    /** Read the explorer again after a statement that changes the schema. */
    val autoSync: Boolean = true,
    /** Read the explorer again every this many minutes. */
    val introspectionMinutes: Int = 0,
)

@Serializable
enum class SshAuth { PASSWORD, KEY }

/** An SSH tunnel to reach the database through a jump host. The password (or key passphrase) is a secret and lives in the keychain, not here. */
@Serializable
data class SshSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: SshAuth = SshAuth.PASSWORD,
    /** Private key file for [SshAuth.KEY]. */
    val keyFile: String = "",
)

/** How far the server's certificate is checked. */
@Serializable
enum class SslVerify { NONE, CA, FULL }

/** What the "SSL" section adds to the plain "encrypted or not" switch: how the server is verified and the certificate files (PEM). */
@Serializable
data class SslSettings(
    val verify: SslVerify = SslVerify.NONE,
    /** Certificate authority that signed the server's certificate. */
    val caFile: String = "",
    /** Client certificate and its private key, for servers that ask for one. */
    val clientCertFile: String = "",
    val clientKeyFile: String = "",
)

/**
 * Which schemas (databases, users, keyspaces) the explorer lists. By default every one the engine shows; with [all] off only the
 * [selected] ones, system schemas included if chosen. A [pattern] (names with `*` and `?`, separated by commas or spaces) also lets
 * matching schemas in, or, with [all] on, restricts the list to them.
 */
@Serializable
data class SchemaFilter(val all: Boolean = true, val selected: List<String> = emptyList(), val pattern: String = "") {
    /** No narrowing at all: the plain listing. */
    val isDefault: Boolean get() = all && pattern.isBlank()

    private val globs: List<Regex> get() = pattern.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        .map { g -> Regex(g.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE) }

    /** Whether the schema called [label] is listed. */
    fun accepts(label: String): Boolean {
        val byPattern = pattern.isNotBlank() && globs.any { it.matches(label) }
        return when {
            all -> pattern.isBlank() || byPattern
            else -> selected.any { it.equals(label, true) } || byPattern
        }
    }
}

@Serializable
data class DataSource(
    val id: String,
    val name: String,
    val kind: DbKind,
    val url: String,
    val user: String? = null,
    /** Transactions are confirmed by hand (Commit/Rollback) instead of after every statement. */
    val manualTx: Boolean = false,
    val options: ConnectionOptions = ConnectionOptions(),
    val ssh: SshSettings = SshSettings(),
    val ssl: SslSettings = SslSettings(),
    val schemas: SchemaFilter = SchemaFilter(),
    /** The SSH password or key passphrase, carried with the connection while it is open; never written to connections.json. */
    @kotlinx.serialization.Transient val sshSecret: String? = null,
    /** The folder it is listed under in the explorer; blank = none. One level only. */
    val folder: String = "",
    /** A key of the app's connection colour palette; null = none. */
    val color: String? = null,
)

data class Column(val name: String, val typeName: String, val nullable: Boolean = true)

data class ResultTable(val columns: List<Column>, val rows: List<List<Any?>>)
