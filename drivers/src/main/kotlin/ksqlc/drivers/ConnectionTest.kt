package ksqlc.drivers

import ksqlc.core.DataSource
import java.sql.DriverManager

/** Opens a connection and returns what answered, e.g. "PostgreSQL 16.4"; throws the driver's exception otherwise. */
fun testConnection(ds: DataSource, password: String?): String {
    DriverManager.setLoginTimeout(10)
    return DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        val m = c.metaData
        "${m.databaseProductName} ${m.databaseProductVersion}".trim()
    }
}
