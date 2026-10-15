package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

class AlterationsServerTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema alt",
                "create table alt.parent(id int primary key)",
                "create table alt.child(id int primary key, parent_id int constraint child_parent_fk references alt.parent(id))",
                "create index child_parent_idx on alt.child(parent_id)",
                "create table alt.tmp(id int)",
                "create sequence alt.seq",
                "create view alt.v as select 1 as x",
                "create materialized view alt.mv as select count(*) as n from alt.tmp",
                "insert into alt.tmp values (1), (2)",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val ns = Namespace(null, "alt")
    private fun table(name: String) = DbObject(ns, name, DbObjectKind.TABLE)
    private fun run(sql: String?) = Session(fx.ds, fx.password).use { s ->
        val r = s.runScript(listOf(assertNotNull(sql))).single()
        assertFalse(r is StatementResult.Failed, (r as? StatementResult.Failed)?.message)
    }
    private fun count(sql: String) = Session(fx.ds, fx.password).use { s ->
        (s.runScript(listOf(sql)).single() as StatementResult.Rows).let { it.cursor.fetch(1).single().single().toString().toInt() }
    }

    @Test fun foreignKeyAndIndexGoAway() {
        val k = DbKind.POSTGRES
        Introspector(fx.ds, fx.password).use { i ->
            val d = i.details(table("child"))
            assertTrue(d.foreignKeys.any { it.name == "child_parent_fk" }); assertTrue(d.indexes.any { it.name == "child_parent_idx" })
        }
        run(dropForeignKeySql(k, table("child"), "child_parent_fk"))
        run(dropIndexSql(k, table("child"), "child_parent_idx"))
        Introspector(fx.ds, fx.password).use { i ->
            val d = i.details(table("child"))
            assertTrue(d.foreignKeys.isEmpty()); assertTrue(d.indexes.none { it.name == "child_parent_idx" })
        }
    }

    @Test fun truncateRefreshAndDrop() {
        val k = DbKind.POSTGRES
        val mv = DbObject(ns, "mv", DbObjectKind.MATERIALIZED_VIEW)
        assertEquals(0, count("select n from alt.mv"))    // built before the inserts, so it still says 0
        run(refreshMatViewSql(k, mv))
        assertEquals(2, count("select n from alt.mv"))
        run(truncateSql(k, table("tmp")))
        assertEquals(0, count("select count(*) from alt.tmp"))
        for (o in listOf(mv, DbObject(ns, "v", DbObjectKind.VIEW), DbObject(ns, "seq", DbObjectKind.SEQUENCE), table("tmp"))) run(dropSql(k, o))
        Introspector(fx.ds, fx.password).use { i -> assertEquals(setOf("child", "parent"), i.objects(ns).map { it.name }.toSet()) }
    }
}
