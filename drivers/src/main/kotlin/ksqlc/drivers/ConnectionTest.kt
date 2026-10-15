package ksqlc.drivers

import ksqlc.core.DataSource
import java.sql.DriverManager

/**
 * A new JDBC connection to [ds]. Cassandra's driver gives each request 2 s by default, too little for DDL or a scan on a busy
 * node, so it is raised to 30 s (the wrapper reads `requestTimeout` in milliseconds).
 */
fun openConnection(source: DataSource, password: String?): java.sql.Connection {
    val ds = throughTunnel(source)
    if (ds.kind != ksqlc.core.DbKind.CASSANDRA) return DriverManager.getConnection(ds.url, ds.user, password)
    val props = java.util.Properties().apply { ds.user?.let { setProperty("user", it) }; password?.let { setProperty("password", it) }; setProperty("requestTimeout", "30000") }
    return DriverManager.getConnection(ds.url, props)
}

/** With an SSH tunnel on, the same data source aimed at the local end of the tunnel; otherwise [ds] itself. */
internal fun throughTunnel(ds: DataSource): DataSource {
    if (!ds.ssh.enabled || ds.kind == ksqlc.core.DbKind.SQLITE) return ds
    val parts = JdbcUrl.parse(ds.url) ?: throw java.sql.SQLException("El túnel SSH necesita una conexión definida con host y puerto, no una URL manual")
    val port = try {
        Tunnels.forward(ds.ssh, ds.sshSecret, parts.host, parts.port ?: JdbcUrl.defaultPort(ds.kind) ?: error("sin puerto"))
    } catch (e: Exception) { throw java.sql.SQLException("No se pudo abrir el túnel SSH: ${e.message}", e) }
    return ds.copy(url = JdbcUrl.build(ds.kind, "127.0.0.1", port, parts.database, parts.ssl, parts.trustCert, parts.datacenter))
}

/** Opens a connection and returns what answered, e.g. "PostgreSQL 16.4"; throws the driver's exception otherwise. */
fun testConnection(ds: DataSource, password: String?): String {
    DriverManager.setLoginTimeout(10)
    return openConnection(ds, password).use { c ->
        val m = c.metaData
        "${m.databaseProductName} ${m.databaseProductVersion}".trim()
    }
}
