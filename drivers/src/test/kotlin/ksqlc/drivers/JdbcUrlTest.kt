package ksqlc.drivers

import ksqlc.core.DbKind.*
import kotlin.test.*

class JdbcUrlTest {
    @Test fun postgresWithDefaultsAndSsl() {
        assertEquals("jdbc:postgresql://localhost:5432/app", JdbcUrl.build(POSTGRES, "", null, "app", false))
        assertEquals("jdbc:postgresql://db:6543/app?sslmode=require", JdbcUrl.build(POSTGRES, "db", 6543, "app", true))
    }
    @Test fun sqliteFileOrMemory() {
        assertEquals(JdbcUrl.SQLITE_SHARED_MEMORY, JdbcUrl.build(SQLITE, "ignored", 1, "", true))
        assertEquals("jdbc:sqlite:/tmp/x.db", JdbcUrl.build(SQLITE, "", null, "/tmp/x.db", false))
    }
    @Test fun otherEngines() {
        assertEquals("jdbc:mysql://h:3306/d", JdbcUrl.build(MYSQL, "h", null, "d", false))
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d;encrypt=true", JdbcUrl.build(MSSQL, "h", null, "d", true))
        assertEquals("jdbc:oracle:thin:@//h:1521/svc", JdbcUrl.build(ORACLE, "h", null, "svc", false))
    }
    @Test fun kindRoundTrip() {
        for (k in entries) assertEquals(k, JdbcUrl.kindOf(JdbcUrl.build(k, "h", null, "d", false)))
    }
}

class JdbcUrlParseTest {
    @Test fun everyBuiltUrlParsesBackToTheSameFields() {
        val cases = listOf(
            JdbcUrl.Parts(POSTGRES, "db.local", 6543, "app", true), JdbcUrl.Parts(POSTGRES, "localhost", 5432, "", false),
            JdbcUrl.Parts(MYSQL, "h", 3306, "d", true), JdbcUrl.Parts(MARIADB, "h", 3307, "d", true),
            JdbcUrl.Parts(MSSQL, "h", 1433, "d", true), JdbcUrl.Parts(MSSQL, "h", 1433, "", false),
            JdbcUrl.Parts(MSSQL, "h", 1433, "d", true, trustCert = true),
            JdbcUrl.Parts(ORACLE, "h", 1521, "svc", false), JdbcUrl.Parts(SQLITE, "", null, "/tmp/x.db", false),
            JdbcUrl.Parts(SQLITE, "", null, "", false),
        )
        for (p in cases) {
            val url = JdbcUrl.build(p.kind, p.host, p.port, p.database, p.ssl, p.trustCert)
            assertEquals(p, JdbcUrl.parse(url), url)
        }
    }

    @Test fun sqlServerTrustsTheCertificateOnlyWhenAskedAndEncrypting() {
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d;encrypt=true;trustServerCertificate=true", JdbcUrl.build(MSSQL, "h", 1433, "d", true, trustCert = true))
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d;encrypt=true", JdbcUrl.build(MSSQL, "h", 1433, "d", true))
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d;encrypt=false", JdbcUrl.build(MSSQL, "h", 1433, "d", false, trustCert = true))   // nothing to trust without encryption
        assertNull(JdbcUrl.parse("jdbc:sqlserver://h:1433;encrypt=false;trustServerCertificate=true"))
    }

    @Test fun urlsItCannotRepresentStayManual() {
        assertNull(JdbcUrl.parse("jdbc:postgresql://h:5432/db?currentSchema=app"))
        assertNull(JdbcUrl.parse("jdbc:postgresql://h/db"))                    // no port: build() would add one
        assertNull(JdbcUrl.parse("jdbc:mysql://h:3306/db?useSSL=false"))
        assertNull(JdbcUrl.parse("jdbc:sqlite:file:other?mode=memory"))
        assertNull(JdbcUrl.parse("jdbc:h2:mem:test"))
    }
}

class ConnectionTestTest {
    @Test fun reportsTheProductOfAWorkingConnection() {
        val ds = ksqlc.core.DataSource("t", "t", ksqlc.core.DbKind.SQLITE, "jdbc:sqlite::memory:")
        assertTrue(testConnection(ds, null).startsWith("SQLite"))
    }

    @Test fun failsWithTheDriversMessageWhenItCannotConnect() {
        val ds = ksqlc.core.DataSource("t", "t", ksqlc.core.DbKind.SQLITE, "jdbc:sqlite:/no/such/dir/x.db")
        assertFailsWith<java.sql.SQLException> { testConnection(ds, null) }
    }
}
