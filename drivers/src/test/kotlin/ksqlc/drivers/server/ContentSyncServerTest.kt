package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

/** The script that makes the right table equal to the left one, applied to a real server and compared again. */
abstract class ContentSyncBase {
    protected abstract val fx: ServerFixture
    protected abstract val ns: Namespace
    /** The SQL that creates tables `<p>_l` and `<p>_r` with a row the same, one that differs and one only on each side (ids 1, 2, 3 left, 4 right). */
    protected abstract fun tables(p: String): List<String>
    private val kind get() = fx.ds.kind
    private fun obj(n: String) = DbObject(ns, n, DbObjectKind.TABLE)

    private fun compare(p: String): ContentDiff = Introspector(fx.ds, fx.password).use { src ->
        Session(fx.ds, fx.password).use { s1 -> Session(fx.ds, fx.password).use { s2 -> diffTableContent(src, s1, obj("${p}_l"), src, s2, obj("${p}_r")) } }
    }
    private fun script(p: String, d: ContentDiff, delete: Boolean = true): List<DmlStatement> {
        val t = Introspector(fx.ds, fx.password).use { syncTarget(it, obj("${p}_r")) }
        return syncStatements(Dialect.of(kind), kind, obj("${p}_r"), d, insert = true, update = true, delete = delete, skip = t.skip, identity = t.identity)
    }
    private fun prepare(p: String): ContentDiff {
        val r = runStepwise(fx.ds, fx.password, tables(p)); assertNull(r.error, r.error)
        return compare(p).also { assertEquals(setOf(RowStatus.DIFFERENT, RowStatus.ONLY_LEFT, RowStatus.ONLY_RIGHT), it.rows.map { r -> r.status }.toSet(), it.rows.toString()) }
    }

    @Test fun applyingTheScriptLeavesNoDifference() {
        val d = prepare("a")
        assertNull(syncProblem(d, partial = false, readOnly = false))
        Session(fx.ds, fx.password).use { s -> s.execute(script("a", d)).getOrThrow() }
        val again = compare("a")
        assertEquals(emptyList(), again.rows, again.rows.toString())
        assertEquals(3, again.same)
    }

    @Test fun withoutDeletingTheRowOnlyOnTheRightStays() {
        val d = prepare("b")
        Session(fx.ds, fx.password).use { s -> s.execute(script("b", d, delete = false)).getOrThrow() }
        assertEquals(listOf(RowStatus.ONLY_RIGHT), compare("b").rows.map { it.status })
    }

    @Test fun aFailureInTheMiddleLeavesTheRightTableAsItWas() {
        val d = prepare("c")
        val bad = DmlStatement("insert into ${Dialect.of(kind).qualified(obj("c_r"))} (id) values (?)", listOf(1), "")   // id 1 exists: fails after the rest ran
        assertTrue(Session(fx.ds, fx.password).use { it.execute(script("c", d) + bad) }.isFailure)
        val after = compare("c")
        assertEquals(d.rows.map { it.status to it.key }, after.rows.map { it.status to it.key })
    }
}

class PostgresContentSyncTest : ContentSyncBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "sy")
    private val cols = "id int primary key, u uuid, j jsonb, e sy.mood, t text, n numeric(10,2), ts timestamptz, b bytea"
    override fun tables(p: String) = listOf(
        "drop table if exists sy.${p}_l", "drop table if exists sy.${p}_r",
        "create table sy.${p}_l($cols)", "create table sy.${p}_r($cols)",
        "insert into sy.${p}_l values (1, '00000000-0000-0000-0000-000000000001', '{\"a\": 1}', 'ok', 'x', 1.50, '2026-01-31 10:00:00+00', '\\x01'), " +
            "(2, '00000000-0000-0000-0000-000000000002', '{\"a\": 2}', 'happy', 'it''s', 2.25, '2026-02-01 10:00:00+00', '\\x0203'), " +
            "(3, null, null, null, null, null, null, null)",
        "insert into sy.${p}_r values (1, '00000000-0000-0000-0000-000000000001', '{\"a\": 1}', 'ok', 'x', 1.50, '2026-01-31 10:00:00+00', '\\x01'), " +
            "(2, '00000000-0000-0000-0000-0000000000ff', '{\"a\": 9}', 'sad', 'old', 9.99, '2026-03-01 10:00:00+00', '\\x09'), " +
            "(4, '00000000-0000-0000-0000-000000000004', '{\"a\": 4}', 'ok', 'gone', 4.00, '2026-01-01 10:00:00+00', null)",
    )

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf("create schema sy", "create type sy.mood as enum ('sad', 'ok', 'happy')"))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class SqlServerContentSyncTest : ContentSyncBase() {
    override val fx get() = Companion.fx
    override val ns = Namespace(null, "dbo")
    private val cols = "id int identity(1,1) primary key, v nvarchar(30) null, d datetime2 null, g uniqueidentifier null, b varbinary(10) null, dbl as (id * 2) persisted"
    override fun tables(p: String) = listOf(
        "drop table if exists dbo.${p}_l", "drop table if exists dbo.${p}_r",
        "create table dbo.${p}_l($cols)", "create table dbo.${p}_r($cols)",
        "set identity_insert dbo.${p}_l on; insert into dbo.${p}_l(id, v, d, g, b) values (1, N'x', '2026-01-31 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC964FF', 0x01), " +
            "(2, N'it''s', '2026-02-01 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC964F0', 0x0203), (3, null, null, null, null); set identity_insert dbo.${p}_l off",
        "set identity_insert dbo.${p}_r on; insert into dbo.${p}_r(id, v, d, g, b) values (1, N'x', '2026-01-31 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC964FF', 0x01), " +
            "(2, N'old', '2026-03-01 10:00:00', '6F9619FF-8B86-D011-B42D-00C04FC96400', 0x09), (4, N'gone', '2026-01-01 10:00:00', null, null); set identity_insert dbo.${p}_r off",
    )

    @Test fun theIdentityAndTheComputedColumnAreRecognisedOnTheTarget() {
        val d = Introspector(fx.ds, fx.password).use { syncTarget(it, DbObject(ns, "t_probe", DbObjectKind.TABLE)) }
        assertEquals(emptySet(), d.skip)    // a table that does not exist reads as having nothing special
        runStepwise(fx.ds, fx.password, tables("probe"))
        val t = Introspector(fx.ds, fx.password).use { syncTarget(it, DbObject(ns, "probe_r", DbObjectKind.TABLE)) }
        assertEquals(setOf("dbl"), t.skip); assertEquals(setOf("id"), t.identity)
    }

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
