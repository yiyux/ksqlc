package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.trino.TrinoContainer
import kotlin.test.*

/** The Trino dialect against a real server (its built-in `tpch` catalog): catalog.schema namespaces, columns, `show create` and a plain query. */
class TrinoServerTest {
    companion object {
        private var container: TrinoContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = TrinoContainer("trinodb/trino:484").also { it.start() }
            container = c
            fx = fixture(DbKind.TRINO, "jdbc:trino://${c.host}:${c.firstMappedPort}", "test", "", emptyList())
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val nation = DbObject(Namespace("tpch", "tiny"), "nation", DbObjectKind.TABLE)

    @Test fun listsCatalogSchemasWithoutTheSystemOnes() {
        val labels = Introspector(fx.ds, fx.password).use { it.namespaces() }.map { it.label }
        assertTrue("tpch.tiny" in labels, labels.toString())
        assertTrue(labels.none { it.startsWith("system.") || it.endsWith(".information_schema") }, labels.toString())
    }

    @Test fun listsTablesAndTheirColumns() {
        val i = Introspector(fx.ds, fx.password)
        assertTrue(i.use { it.objects(nation.ns) }.any { it.name == "nation" })
        val d = Dialect.of(DbKind.TRINO).let { d -> openConnection(fx.ds, fx.password).use { d.details(it, nation) } }
        assertEquals(listOf("nationkey", "name", "regionkey", "comment"), d.columns.map { it.name })
    }

    @Test fun rebuildsDdlWithShowCreate() {
        val ddl = openConnection(fx.ds, fx.password).use { Dialect.of(DbKind.TRINO).ddl(it, nation) }
        assertTrue(ddl.lowercase().startsWith("create table tpch.tiny.nation"), ddl)
    }

    @Test fun runsAQueryWithoutTransactions() {
        Session(fx.ds, fx.password).use { s ->
            assertTrue(s.autoCommit)
            val r = s.runScript(listOf("select count(*) from tpch.tiny.nation")).single() as StatementResult.Rows
            assertEquals("25", r.cursor.use { it.fetch(1) }.single().single().toString())
        }
    }
}
