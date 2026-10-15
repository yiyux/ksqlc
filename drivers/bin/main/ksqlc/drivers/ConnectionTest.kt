package ksqlc.drivers

import ksqlc.core.DataSource
import java.sql.DriverManager

/**
 * A new JDBC connection to [ds]. Cassandra's driver gives each request 2 s by default, too little for DDL or a scan on a busy
 * node, so it is raised to 30 s (the wrapper reads `requestTimeout` in milliseconds).
 */
fun openConnection(ds: DataSource, password: String?): java.sql.Connection {
    if (ds.kind != ksqlc.core.DbKind.CASSANDRA) return DriverManager.getConnection(ds.url, ds.user, password)
    val props = java.util.Properties().apply { ds.user?.let { setProperty("user", it) }; password?.let { setProperty("password", it) }; setProperty("requestTimeout", "30000") }
    return DriverManager.getConnection(ds.url, props)
}

/** Opens a connection and returns what answered, e.g. "PostgreSQL 16.4"; throws the driver's exception otherwise. */
fun testConnection(ds: DataSource, password: String?): String {
    DriverManager.setLoginTimeout(10)
    return openConnection(ds, password).use { c ->
        val m = c.metaData
        "${m.databaseProductName} ${m.databaseProductVersion}".trim()
    }
}
