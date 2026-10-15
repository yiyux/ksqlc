package ksqlc.drivers

import ksqlc.core.DbKind

object JdbcUrl {
    const val SQLITE_SHARED_MEMORY = "jdbc:sqlite:file:ksqlc-mem?mode=memory&cache=shared"

    fun defaultPort(kind: DbKind): Int? = when (kind) {
        DbKind.POSTGRES -> 5432
        DbKind.MYSQL, DbKind.MARIADB -> 3306
        DbKind.MSSQL -> 1433
        DbKind.ORACLE -> 1521
        DbKind.CASSANDRA -> 9042
        DbKind.SQLITE -> null
    }

    fun kindOf(url: String): DbKind = when {
        url.startsWith("jdbc:postgresql") -> DbKind.POSTGRES
        url.startsWith("jdbc:mysql") -> DbKind.MYSQL
        url.startsWith("jdbc:mariadb") -> DbKind.MARIADB
        url.startsWith("jdbc:sqlserver") -> DbKind.MSSQL
        url.startsWith("jdbc:oracle") -> DbKind.ORACLE
        url.startsWith("jdbc:cassandra") -> DbKind.CASSANDRA
        else -> DbKind.SQLITE
    }

    /**
     * For SQLite [database] is the file path (blank = a named in-memory database shared by every
     * connection in this process, so the console and the schema tree see the same tables) and
     * host/port/ssl are ignored.
     * For Oracle [database] is the service name.
     * For Cassandra [database] is the keyspace (may be blank) and [datacenter] the local datacenter its driver needs (default `datacenter1`).
     * For SQL Server, [trustCert] (only with [ssl]) accepts the server's certificate without validating it, which a self-signed one needs.
     */
    fun build(kind: DbKind, host: String, port: Int?, database: String, ssl: Boolean, trustCert: Boolean = false, datacenter: String = ""): String {
        val h = host.ifBlank { "localhost" }
        val p = port ?: defaultPort(kind)
        return when (kind) {
            DbKind.SQLITE -> if (database.isBlank()) SQLITE_SHARED_MEMORY else "jdbc:sqlite:$database"
            DbKind.POSTGRES -> "jdbc:postgresql://$h:$p/$database" + if (ssl) "?sslmode=require" else ""
            DbKind.MYSQL -> "jdbc:mysql://$h:$p/$database" + if (ssl) "?sslMode=REQUIRED" else ""
            DbKind.MARIADB -> "jdbc:mariadb://$h:$p/$database" + if (ssl) "?sslMode=trust" else ""
            DbKind.MSSQL -> "jdbc:sqlserver://$h:$p" + (if (database.isBlank()) "" else ";databaseName=$database") +
                ";encrypt=" + ssl + (if (ssl && trustCert) ";trustServerCertificate=true" else "")
            DbKind.ORACLE -> "jdbc:oracle:thin:@//$h:$p/$database"
            DbKind.CASSANDRA -> "jdbc:cassandra://$h:$p" + (if (database.isBlank()) "" else "/$database") + "?localdatacenter=${datacenter.ifBlank { DEFAULT_DATACENTER }}" + if (ssl) "&enableSsl=true" else ""
        }
    }

    const val DEFAULT_DATACENTER = "datacenter1"

    /** The form fields a URL built by [build] came from. */
    data class Parts(val kind: DbKind, val host: String, val port: Int?, val database: String, val ssl: Boolean, val trustCert: Boolean = false, val datacenter: String = "")

    /**
     * Inverse of [build]: recovers the fields of a URL it produced. Returns null for any other URL (extra parameters,
     * unknown shapes), so the caller keeps it as a manual URL instead of silently dropping parts of it.
     */
    fun parse(url: String): Parts? {
        val kind = kindOf(url)
        val parts = when (kind) {
            DbKind.SQLITE -> when {
                url == SQLITE_SHARED_MEMORY -> Parts(kind, "", null, "", false)
                url.startsWith("jdbc:sqlite:") && !url.startsWith("jdbc:sqlite:file:") -> Parts(kind, "", null, url.removePrefix("jdbc:sqlite:"), false)
                else -> null
            }
            DbKind.POSTGRES -> Regex("""^jdbc:postgresql://([^:/?]+):(\d+)/([^?]*)(\?sslmode=require)?$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[4].isNotEmpty()) }
            DbKind.MYSQL -> Regex("""^jdbc:mysql://([^:/?]+):(\d+)/([^?]*)(\?sslMode=REQUIRED)?$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[4].isNotEmpty()) }
            DbKind.MARIADB -> Regex("""^jdbc:mariadb://([^:/?]+):(\d+)/([^?]*)(\?sslMode=trust)?$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[4].isNotEmpty()) }
            DbKind.MSSQL -> Regex("""^jdbc:sqlserver://([^:;/]+):(\d+)(?:;databaseName=([^;]*))?;encrypt=(true|false)(;trustServerCertificate=true)?$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[4] == "true", it.groupValues[5].isNotEmpty()) }
            DbKind.ORACLE -> Regex("""^jdbc:oracle:thin:@//([^:/]+):(\d+)/(.*)$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], false) }
            DbKind.CASSANDRA -> Regex("""^jdbc:cassandra://([^:/?]+):(\d+)(?:/([^?]+))?\?localdatacenter=([^&]+)(&enableSsl=true)?$""").matchEntire(url)?.let { Parts(kind, it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[5].isNotEmpty(), datacenter = it.groupValues[4]) }
        }
        return parts?.takeIf { build(it.kind, it.host, it.port, it.database, it.ssl, it.trustCert, it.datacenter) == url }
    }
}
