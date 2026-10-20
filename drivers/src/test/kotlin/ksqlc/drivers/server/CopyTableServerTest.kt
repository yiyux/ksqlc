package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

/** A table copied to a real server, by creating it or by adding to one that is there, and compared again. */
abstract class CopyTableBase {
    protected abstract val fx: ServerFixture
    protected abstract val ns: Namespace
    /** The SQL that creates `<p>_src` with rows 1, 2 and 3 (the last one with nulls) and, when [target] is true, an empty `<p>_dst` shaped for the copy. */
    protected abstract fun tables(p: String, target: Boolean): List<String>
    private val kind get() = fx.ds.kind
    private val dialect get() = Dialect.of(kind)
    private fun obj(n: String) = DbObject(ns, n, DbObjectKind.TABLE)

    private fun copy(p: String, createIt: Boolean): Long = Introspector(fx.ds, fx.password).use { src ->
        val s = obj("${p}_src"); val d = obj("${p}_dst")
        val details = src.details(s)
        val t = if (createIt) SyncTarget(emptySet(), emptySet()) else syncTarget(src, d)
        val cols = copyColumns(details, if (createIt) null else src.details(d), t.skip)
        val create = if (createIt) dialect.createTable(d, details) else null
        Session(fx.ds, fx.password).use { a -> Session(fx.ds, fx.password, autoCommit = true).use { b ->
            copyRows(a, dialect, s, cols.from, b, dialect, kind, d, cols.to, t.identity, create).getOrThrow()
        } }
    }
    private fun diff(p: String): ContentDiff = Introspector(fx.ds, fx.password).use { src ->
        Session(fx.ds, fx.password).use { s1 -> Session(fx.ds, fx.password).use { s2 -> diffTableContent(src, s1, obj("${p}_src"), src, s2, obj("${p}_dst")) } }
    }

    @Test fun createsTheTableAndCopiesTheRowsExactly() {
        assertNull(runStepwise(fx.ds, fx.password, tables("a", target = false)).error)
        assertEquals(3L, copy("a", createIt = true))
        val d = diff("a")
        assertEquals(emptyList(), d.rows, d.rows.toString())
        assertEquals(3, d.same)
    }

    @Test fun addsToAnExistingTableKeepingIdentityValues() {
        assertNull(runStepwise(fx.ds, fx.password, tables("b", target = true)).error)
        assertEquals(3L, copy("b", createIt = false))
        assertEquals(emptyList(), diff("b").rows)
    }

    @Test fun aSecondCopyFailsOnTheKeyAndLeavesTheTableAsItWas() {
        assertNull(runStepwise(fx.ds, fx.password, tables("c", target = true)).error)
        copy("c", createIt = false)
        assertTrue(runCatching { copy("c", createIt = false) }.isFailure)
        assertEquals(3, diff("c").same)
    }
}

class PostgresCopyTableTest : CopyTableBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "cp")
    private val cols = "id int primary key, u uuid, j jsonb, t text not null, n numeric(10,2), ts timestamptz, b bytea"
    override fun tables(p: String, target: Boolean) = listOf(
        "drop table if exists cp.${p}_src", "drop table if exists cp.${p}_dst",
        "create table cp.${p}_src($cols)",
        "insert into cp.${p}_src values (1, '00000000-0000-0000-0000-000000000001', '{\"a\": 1}', 'x', 1.50, '2026-01-31 10:00:00+00', '\\x01'), " +
            "(2, '00000000-0000-0000-0000-000000000002', '{\"a\": 2}', 'it''s', 2.25, '2026-02-01 10:00:00+00', '\\x0203'), (3, null, null, 'n', null, null, null)",
    ) + if (target) listOf("create table cp.${p}_dst($cols)") else emptyList()

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf("create schema cp"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class SqlServerCopyTableTest : CopyTableBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "dbo")
    private val cols = "id int identity(1,1) primary key, v nvarchar(30) null, d datetime2 null, g uniqueidentifier null, b varbinary(10) null, dbl as (id * 2) persisted"
    override fun tables(p: String, target: Boolean) = listOf(
        "drop table if exists dbo.${p}_src", "drop table if exists dbo.${p}_dst",
        "create table dbo.${p}_src($cols)",
        "set identity_insert dbo.${p}_src on; insert into dbo.${p}_src(id, v, d, g, b) values (1, N'x', '2026-01-31 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC964FF', 0x01), " +
            "(2, N'it''s', '2026-02-01 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC964F0', 0x0203), (3, null, null, null, null); set identity_insert dbo.${p}_src off",
    ) + if (target) listOf("create table dbo.${p}_dst($cols)") else emptyList()

    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            fx = fixture(DbKind.MSSQL, JdbcUrl.build(DbKind.MSSQL, c.host, c.getMappedPort(1433), "", false), c.username, c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
