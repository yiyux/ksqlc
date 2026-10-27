package ksqlc.drivers

import ksqlc.core.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class ConnectionOptionsSupportTest {
    @Test fun startupIsTheTimeZoneThenTheScriptSplitAsTheConsoleDoes() {
        val o = ConnectionOptions(timeZone = "Europe/Madrid", startupScript = "set search_path to app;\nset statement_timeout = '5s'")
        assertEquals(listOf("set time zone 'Europe/Madrid'", "set search_path to app", "set statement_timeout = '5s'"), sessionStartup(DbKind.POSTGRES, o))
        assertEquals(listOf("set time_zone = '+01:00'"), sessionStartup(DbKind.MYSQL, ConnectionOptions(timeZone = "+01:00")))
        assertEquals(listOf("alter session set time_zone = 'it''s'"), sessionStartup(DbKind.ORACLE, ConnectionOptions(timeZone = "it's")))
        assertEquals(emptyList(), sessionStartup(DbKind.SQLITE, ConnectionOptions(timeZone = "UTC")))      // no session time zone to set
        assertEquals(listOf("select 1"), sessionStartup(DbKind.SQLITE, ConnectionOptions(startupScript = "select 1;")))
        assertEquals(listOf("use a", "use b"), sessionStartup(DbKind.MSSQL, ConnectionOptions(startupScript = "use a\nGO\nuse b")))
        assertEquals(emptyList(), sessionStartup(DbKind.POSTGRES, ConnectionOptions()))
        assertEquals(listOf("set default_transaction_read_only = on"), sessionStartup(DbKind.POSTGRES, ConnectionOptions(readOnly = true)))
        assertEquals(listOf("set session transaction read only"), sessionStartup(DbKind.MARIADB, ConnectionOptions(readOnly = true)))
        assertEquals(listOf("pragma query_only = on"), sessionStartup(DbKind.SQLITE, ConnectionOptions(readOnly = true)))
        assertEquals(emptyList(), sessionStartup(DbKind.ORACLE, ConnectionOptions(readOnly = true)))      // no session-wide switch: the text check alone
    }

    @Test fun whichOptionsEachEngineOffers() {
        assertEquals(setOf(DbKind.POSTGRES, DbKind.MYSQL, DbKind.MARIADB, DbKind.ORACLE), DbKind.entries.filter(::supportsTimeZone).toSet())
        assertEquals(setOf(DbKind.MYSQL, DbKind.MARIADB, DbKind.CASSANDRA, DbKind.ORACLE), DbKind.entries.filter(::supportsSingleDatabase).toSet())
        assertEquals(DbKind.entries.toSet() - DbKind.CASSANDRA - DbKind.TRINO, DbKind.entries.filter(::supportsTransactions).toSet())
    }

    @Test fun onlyReadsPassTheReadOnlyCheck() {
        for (ok in listOf("select 1", "  -- c\nWITH x AS (select 1) select * from x", "show tables", "explain select 1", "describe t", "values (1)", "use app", "with a as (select 1) select * from a where x = 'insert'")) assertNull(readOnlyViolation(ok), ok)
        for (bad in listOf("insert into t values (1)", "update t set a = 1", "delete from t", "drop table t", "create table t(a int)", "set transaction read write", "pragma user_version = 2", "call p()", "truncate t", "", "with i as (insert into t values (1) returning 1) select * from i", "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d")) assertNotNull(readOnlyViolation(bad), bad)
    }

    @Test fun keepAliveQueryByEngine() {
        assertEquals("select 1 from dual", keepAliveSql(DbKind.ORACLE))
        assertEquals("select release_version from system.local", keepAliveSql(DbKind.CASSANDRA))
        assertEquals("select 1", keepAliveSql(DbKind.POSTGRES))
    }
}

class SessionOptionsTest {
    private val file = Files.createTempFile("ksqlc-opts", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"
    private fun ds(o: ConnectionOptions) = DataSource("t", "t", DbKind.SQLITE, url, options = o)

    init { DriverManager.getConnection(url).use { it.createStatement().execute("create table t(id integer primary key, v text)"); it.createStatement().execute("insert into t values (1, 'a')") } }

    private fun count() = DriverManager.getConnection(url).use { c -> c.createStatement().executeQuery("select count(*) from t").let { it.next(); it.getInt(1) } }

    @Test fun readOnlyRefusesWritesEverywhereButStillReads() {
        Session(ds(ConnectionOptions(readOnly = true)), null).use { s ->
        assertTrue(s.runScript(listOf("select * from t")).single() is StatementResult.Rows)
        val failed = s.runScript(listOf("insert into t values (2, 'b')")).single()
        assertTrue(failed is StatementResult.Failed && "solo lectura" in failed.message, failed.toString())
        assertTrue(s.execute(listOf(DmlStatement("insert into t values (?, ?)", listOf(2, "b"), "insert"))).isFailure)
        assertTrue(s.executeBatch("insert into t values (?, ?)", listOf(listOf<Any?>(3, "c")).iterator()).isFailure)
        assertEquals(1, count())
        }
    }

    @Test fun withoutTheOptionWritesWork() {
        Session(ds(ConnectionOptions()), null).use { s ->
        assertTrue(s.runScript(listOf("insert into t values (9, 'z')")).single() is StatementResult.Updated)
        assertEquals(2, count())
        DriverManager.getConnection(url).use { it.createStatement().execute("delete from t where id = 9") }
        }
    }

    @Test fun theStartupScriptRunsOnEveryNewConnection() {
        Session(ds(ConnectionOptions(startupScript = "create temp table boot(x); insert into boot values (42);")), null).use { s ->
        val rows = s.runScript(listOf("select x from boot")).single() as StatementResult.Rows      // a temp table only exists on the connection that created it
        assertEquals(42, (rows.cursor.fetch(1).single().single() as Number).toInt())
        rows.cursor.close()
        }
    }

    @Test fun aFailingStartupScriptIsReportedAndOpensNothing() {
        Session(ds(ConnectionOptions(startupScript = "select * from no_such_table")), null).use { s ->
        val failed = s.runScript(listOf("select 1")).single()
        assertTrue(failed is StatementResult.Failed && "script de inicio" in failed.message.lowercase(), failed.toString())
        }
    }

    @Test fun keepAliveRunsOnlyOnAnOpenIdleConnection() {
        Session(ds(ConnectionOptions()), null).use { s ->
        assertFalse(s.keepAlive())      // nothing open yet
        val rows = s.runScript(listOf("select 1")).single() as StatementResult.Rows
        assertFalse(s.keepAlive())      // a result is still open
        rows.cursor.close()
        s.runScript(listOf("select 1")).forEach { (it as? StatementResult.Rows)?.cursor?.close() }
        assertTrue(s.keepAlive()); assertEquals(1, s.keepAlives)
        }
    }

    @Test fun lastUsedMovesWithEveryStatement() {
        Session(ds(ConnectionOptions()), null).use { s ->
        val before = s.lastUsed
        Thread.sleep(20)
        s.runScript(listOf("select 1")).forEach { (it as? StatementResult.Rows)?.cursor?.close() }
        assertTrue(s.lastUsed > before)
        }
    }
}
