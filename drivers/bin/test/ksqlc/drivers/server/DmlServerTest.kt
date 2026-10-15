package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

/** Edit → send → read back against real servers, with typed values bound as parameters. */
abstract class DmlServerBase {
    protected abstract val fx: ServerFixture
    protected abstract val ns: Namespace
    private val kind get() = fx.ds.kind
    private val dialect get() = Dialect.of(kind)
    private fun obj(name: String) = DbObject(ns, name, DbObjectKind.TABLE)
    private fun t(s: String) = CellValue.Text(s)

    private fun read(s: Session, table: String): StatementResult.Rows =
        s.runScript(listOf("select * from ${dialect.qualified(obj(table))} order by 1")).single() as StatementResult.Rows

    private fun send(s: Session, table: String, pk: List<String> = listOf("id"), updates: (List<List<Any?>>) -> List<RowEdit> = { emptyList() },
                     inserts: List<Map<String, CellValue>> = emptyList(), deletes: (List<List<Any?>>) -> List<Int> = { emptyList() }) {
        val r = read(s, table); val rows = r.cursor.fetch(100); r.cursor.close()
        val dml = buildDml(dialect, kind, obj(table), r.columns, pk, updates(rows), inserts, deletes(rows).map { rows[it] })
        s.execute(dml).getOrThrow()
    }

    @Test fun typedValuesRoundTrip() = Session(fx.ds, fx.password).use { s ->
        val values = mutableMapOf<String, CellValue>(
            "id" to t("1"), "qty" to t("5"), "price" to t("12.50"), "active" to t("true"), "born" to t("2026-01-31"),
            "seen" to t("2026-01-31 10:20:30"), "note" to t("it's \"quoted\""),
        )
        extra()?.let { values += it }
        send(s, "items", inserts = listOf(values))
        val r = read(s, "items"); val row = r.cursor.fetch(10).single()
        val byName = r.columns.indices.associate { r.columns[it].name to row[it] }
        assertEquals(5, (byName["qty"] as Number).toInt())
        assertEquals(0, java.math.BigDecimal("12.50").compareTo(byName["price"] as java.math.BigDecimal))
        assertEquals("it's \"quoted\"", byName["note"])
        assertTrue(byName["born"].toString().startsWith("2026-01-31"))
        assertTrue(byName["seen"].toString().replace('T', ' ').startsWith("2026-01-31 10:20:30"), byName["seen"].toString())

        // update two columns, one of them to NULL
        send(s, "items", updates = { rows -> listOf(RowEdit(rows[0], mapOf("price" to t("13.75"), "note" to CellValue.Null))) })
        val r2 = read(s, "items"); val row2 = r2.cursor.fetch(10).single()
        assertEquals(0, java.math.BigDecimal("13.75").compareTo(row2[r2.columns.indexOfFirst { it.name == "price" }] as java.math.BigDecimal))
        assertNull(row2[r2.columns.indexOfFirst { it.name == "note" }])

        // delete
        send(s, "items", deletes = { listOf(0) })
        assertEquals(emptyList(), read(s, "items").cursor.fetch(10))
    }

    @Test fun anInsertWithNothingUsesTheDefaults() = Session(fx.ds, fx.password).use { s ->
        send(s, "defaults", inserts = listOf(mapOf("id" to CellValue.Default, "n" to CellValue.Default)))
        val r = read(s, "defaults"); val row = r.cursor.fetch(10).single()
        assertEquals(7, (row[r.columns.indexOfFirst { it.name == "n" }] as Number).toInt())
    }

    @Test fun compositeKeysIdentifyTheRightRow() = Session(fx.ds, fx.password).use { s ->
        send(s, "pairs", pk = listOf("a", "b"), inserts = listOf(mapOf("a" to t("1"), "b" to t("1"), "v" to t("x")), mapOf("a" to t("1"), "b" to t("2"), "v" to t("y"))))
        send(s, "pairs", pk = listOf("a", "b"), updates = { rows -> listOf(RowEdit(rows[1], mapOf("v" to t("changed")))) })
        val r = read(s, "pairs"); val rows = r.cursor.fetch(10)
        assertEquals(listOf("x", "changed"), rows.map { it[2] })
    }

    @Test fun aBadValueFailsBeforeAnythingIsSent() {
        val s = Session(fx.ds, fx.password)
        s.use {
            val r = read(s, "items")
            assertFailsWith<DmlException> { buildDml(dialect, kind, obj("items"), r.columns, listOf("id"), emptyList(), listOf(mapOf("id" to t("abc"))), emptyList()) }
            r.cursor.close()
        }
    }

    /** Server-specific column values (PostgreSQL has a uuid column). */
    protected open fun extra(): Map<String, CellValue>? = null

    @Test fun explainShowsPlansAndPlainExplainNeverChangesData() = Session(fx.ds, fx.password).use { s ->
        val table = dialect.qualified(obj("items"))
        send(s, "items", inserts = listOf(mapOf("id" to t("1"), "qty" to t("1"))))
        fun plan(sql: String, analyze: Boolean) = explainScript(kind, sql, analyze)!!.let { sc ->
            (s.runScript(sc.statements).last() as StatementResult.Rows).let { r -> r.cursor.fetch(100).also { r.cursor.close() } }
        }
        assertTrue(plan("select * from $table", false).isNotEmpty())
        assertTrue(plan("select * from $table", true).isNotEmpty())
        assertTrue(plan("delete from $table", false).isNotEmpty())
        assertEquals(1, read(s, "items").let { r -> r.cursor.fetch(10).also { r.cursor.close() } }.size)      // the delete was only described
    }
}

class PostgresDmlTest : DmlServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "app")
    override fun extra() = mapOf("ref" to CellValue.Text("3f2c1c0e-7d52-4a6e-9a43-0d6a0c1b2c3d"))

    @Test fun uuidIsBoundUntypedAndComesBack() = Session(fx.ds, fx.password).use { s ->
        val r = s.runScript(listOf("select ref from app.items")).single()
        assertTrue(r is StatementResult.Rows)
        (r as StatementResult.Rows).cursor.close()
    }

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema app",
                "create table app.items(id int primary key, qty int, price numeric(10,2), active boolean, born date, seen timestamp, ref uuid, note text)",
                "create table app.defaults(id serial primary key, n int default 7)",
                "create table app.pairs(a int, b int, v text, primary key (a, b))",
            ))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

private val MYSQL_SETUP = listOf(
    "create database app",
    "create table app.items(id int primary key, qty int, price decimal(10,2), active tinyint(1), born date, seen datetime, note text)",
    "create table app.defaults(id int auto_increment primary key, n int default 7)",
    "create table app.pairs(a int, b int, v text, primary key (a, b))",
)

class MySqlDmlTest : DmlServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace("app", null)
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, MYSQL_SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MariaDbDmlTest : DmlServerBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace("app", null)
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, MYSQL_SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
