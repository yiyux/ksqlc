package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.StringReader
import kotlin.test.*

/** Export to CSV and import it back into a twin table, against real servers: every value must survive. */
abstract class TransferServerBase {
    protected abstract val fx: ServerFixture
    protected abstract val ns: Namespace
    private val kind get() = fx.ds.kind
    private val dialect get() = Dialect.of(kind)
    private fun obj(name: String) = DbObject(ns, name, DbObjectKind.TABLE)

    private fun rows(s: Session, table: String): List<List<String?>> {
        val r = s.runScript(listOf("select id, qty, price, born, seen, note from ${dialect.qualified(obj(table))} order by id")).single() as StatementResult.Rows
        return r.cursor.fetch(100).map { row -> row.map { v -> (v as? java.math.BigDecimal)?.toPlainString() ?: v?.toString() } }
    }

    @Test fun csvRoundTripKeepsEveryValue() {
        Session(fx.ds, fx.password).use { s ->
            val src = dialect.qualified(obj("src_t"))
            s.runScript(listOf(
                "insert into $src (id, qty, price, born, seen, note) values (1, 5, 12.50, '2026-01-31', '2026-01-31 10:20:30', 'a, b')",
                "insert into $src (id, qty, price, born, seen, note) values (2, null, null, null, null, null)",
                "insert into $src (id, qty, price, born, seen, note) values (3, -7, 0.05, '1999-12-31', '1999-12-31 23:59:59', 'dice \"hola\" y ''adiós''')",
            )).forEach { assertFalse(it is StatementResult.Failed, it.toString()) }

            val csv = StringBuilder()
            assertEquals(3L, exportQuery(s, "select * from $src order by id", { RowWriter(ExportFormat.CSV, it, csv) }))

            val details = Introspector(fx.ds, fx.password).use { it.details(obj("dst_t")) }
            val reader = CsvReader(StringReader(csv.toString()))
            val head = reader.next()!!
            val mapping = head.map { h -> details.columns.indexOfFirst { it.name.equals(h, ignoreCase = true) }.takeIf { it >= 0 } }
            assertTrue(mapping.all { it != null }, "cabecera ${head}")
            val n = importRows(s, dialect, kind, obj("dst_t"), details.columns, mapping, reader.asSequence().iterator(), 2).getOrThrow()
            assertEquals(3L, n)
            assertEquals(rows(s, "src_t"), rows(s, "dst_t"))
            assertEquals(null, rows(s, "dst_t")[1][5])   // NULL stays NULL, not ""
        }
    }
}

class PostgresTransferTest : TransferServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "app")
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            val cols = "id int primary key, qty int, price numeric(10,2), born date, seen timestamp, note text"
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf("create schema app", "create table app.src_t($cols)", "create table app.dst_t($cols)"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

private val MYSQL_COLS = "id int primary key, qty int, price decimal(10,2), born date, seen datetime, note text"

class MySqlTransferTest : TransferServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace("app", null)
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, listOf("create database app", "create table app.src_t($MYSQL_COLS)", "create table app.dst_t($MYSQL_COLS)"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MariaDbTransferTest : TransferServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace("app", null)
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, listOf("create database app", "create table app.src_t($MYSQL_COLS)", "create table app.dst_t($MYSQL_COLS)"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class SqlServerTransferTest : TransferServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "app")
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            val url = JdbcUrl.build(DbKind.MSSQL, c.host, c.getMappedPort(1433), "", false)
            val cols = "id int primary key, qty int, price decimal(10,2), born date, seen datetime2(0), note nvarchar(max)"
            fx = fixture(DbKind.MSSQL, url, c.username, c.password, listOf("create schema app", "create table app.src_t($cols)", "create table app.dst_t($cols)"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
