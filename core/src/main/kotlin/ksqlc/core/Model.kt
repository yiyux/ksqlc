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
data class DataSource(
    val id: String,
    val name: String,
    val kind: DbKind,
    val url: String,
    val user: String? = null,
    /** Transactions are confirmed by hand (Commit/Rollback) instead of after every statement. */
    val manualTx: Boolean = false,
    val options: ConnectionOptions = ConnectionOptions(),
)

data class Column(val name: String, val typeName: String, val nullable: Boolean = true)

data class ResultTable(val columns: List<Column>, val rows: List<List<Any?>>)
