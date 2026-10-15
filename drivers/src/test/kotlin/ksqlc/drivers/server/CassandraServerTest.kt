package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import ksqlc.drivers.dialect.CassandraDialect
import ksqlc.drivers.dialect.Dialect
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.cassandra.CassandraContainer
import kotlin.test.*

/** The Cassandra dialect against a real node: system_schema introspection, rebuilt DDL, and writes without transactions. */
class CassandraServerTest {
    companion object {
        private var container: CassandraContainer? = null
        private lateinit var fx: ServerFixture
        private var viewsEnabled = false
        private var functionsEnabled = false

        @BeforeClass @JvmStatic fun start() {
            // KSQLC_CASSANDRA=host:port points the test at a node that is already running (no Docker needed); it keeps its `ksqlc` keyspace afterwards
            val external = System.getenv("KSQLC_CASSANDRA")
            val url = if (external != null) "jdbc:cassandra://$external?localdatacenter=datacenter1" else {
                assumeDocker()
                val c = CassandraContainer("cassandra:5.0").also { it.start() }
                container = c
                "jdbc:cassandra://${c.contactPoint.hostString}:${c.contactPoint.port}?localdatacenter=${c.localDatacenter}"
            }
            fx = fixture(DbKind.CASSANDRA, url, "cassandra", "cassandra", listOf(
                "drop keyspace if exists ksqlc",
                "create keyspace ksqlc with replication = {'class': 'SimpleStrategy', 'replication_factor': 1}",
                "create table ksqlc.events(tenant text, day date, at timestamp, id uuid, body text, n int, primary key ((tenant, day), at, id)) with clustering order by (at desc, id asc) and comment = 'Eventos'",
                "create table ksqlc.people(id int primary key, name text, born date, score double, active boolean)",
                "create index people_name_idx on ksqlc.people (name)",
            ))
            // materialized views are off by default in Cassandra 5 (`materialized_views_enabled` in cassandra.yaml)
            viewsEnabled = runCatching {
                openConnection(fx.ds, fx.password).use { it.createStatement().execute("create materialized view ksqlc.people_by_name as select id, name from ksqlc.people where name is not null and id is not null primary key (name, id)") }
            }.isSuccess
            // user-defined functions are off by default too (`user_defined_functions_enabled`)
            functionsEnabled = runCatching {
                openConnection(fx.ds, fx.password).use { it.createStatement().execute("create or replace function ksqlc.twice(n int) called on null input returns int language java as 'return n == null ? null : n * 2;'") }
            }.isSuccess
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val ns = Namespace(null, "ksqlc")
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun obj(name: String, kind: DbObjectKind = DbObjectKind.TABLE) = DbObject(ns, name, kind)

    @Test fun systemKeyspacesAppearOnlyWhenAsked() = intro().use { i ->
        i.includeSystem = true
        val names = i.namespaces().map { it.label }
        assertTrue("system_schema" in names && "system" in names && "ksqlc" in names, names.toString())
        assertTrue(i.objects(i.namespaces().first { it.label == "system_schema" }).any { it.name == "tables" })
    }

    @Test fun singleDatabaseListsOnlyTheNamedKeyspace() = intro().use { i ->
        i.onlyNamespace = "KSQLC"
        assertEquals(listOf("ksqlc"), i.namespaces().map { it.label })
    }

    @Test fun usesTheNativeDialect() = assertSame(CassandraDialect, Dialect.of(DbKind.CASSANDRA))

    @Test fun keyspacesAreNamespacesWithoutTheSystemOnes() = intro().use { i ->
        val names = i.namespaces().map { it.label }
        assertTrue("ksqlc" in names, names.toString())
        assertTrue(names.none { it.startsWith("system") }, names.toString())
    }

    @Test fun objectsAreTablesAndMaterializedViews() = intro().use { i ->
        val objects = i.objects(ns).associate { it.name to it.kind }
        assertEquals(DbObjectKind.TABLE, objects["events"]); assertEquals(DbObjectKind.TABLE, objects["people"])
        if (viewsEnabled) assertEquals(DbObjectKind.MATERIALIZED_VIEW, objects["people_by_name"])
        if (functionsEnabled) assertEquals(DbObjectKind.ROUTINE, objects["twice"])
    }

    @Test fun primaryKeyIsPartitionThenClusteringAndIndexesAreListed() = intro().use { i ->
        val d = i.details(obj("events"))
        assertEquals(listOf("tenant", "day", "at", "id"), d.primaryKey)
        assertEquals(listOf("tenant", "day", "at", "id", "body", "n"), d.columns.map { it.name })
        assertFalse(d.columns.first { it.name == "tenant" }.nullable); assertTrue(d.columns.first { it.name == "body" }.nullable)
        assertEquals("timestamp", d.columns.first { it.name == "at" }.typeName)
        assertEquals(listOf("people_name_idx"), i.details(obj("people")).indexes.map { it.name })
    }

    @Test fun ddlIsRebuiltFromTheSystemSchema() = intro().use { i ->
        val ddl = i.ddl(obj("events"))
        assertTrue("PRIMARY KEY ((\"tenant\", \"day\"), \"at\", \"id\")" in ddl, ddl)
        assertTrue("CLUSTERING ORDER BY (\"at\" DESC, \"id\" ASC)" in ddl, ddl)
        assertTrue("comment = 'Eventos'" in ddl, ddl)
        assertTrue("CREATE INDEX \"people_name_idx\"" in i.ddl(obj("people")))
        if (functionsEnabled) assertTrue("CREATE OR REPLACE FUNCTION \"ksqlc\".\"twice\" (n int)" in i.ddl(obj("twice", DbObjectKind.ROUTINE)), i.ddl(obj("twice", DbObjectKind.ROUTINE)))
        if (viewsEnabled) assertTrue("CREATE MATERIALIZED VIEW \"ksqlc\".\"people_by_name\"" in i.ddl(obj("people_by_name", DbObjectKind.MATERIALIZED_VIEW)))
    }

    @Test fun consoleStatementsRunAndEditsAreSentAsUpsertsAndDeletes() = Session(fx.ds, fx.password).use { s ->
        val people = obj("people")
        fun read() = (s.runScript(listOf("select * from ksqlc.people")).single() as StatementResult.Rows).let { r -> r.columns to r.cursor.fetch(100).also { r.cursor.close() } }
        val dialect = Dialect.of(DbKind.CASSANDRA)
        assertTrue(s.runScript(listOf("insert into ksqlc.people (id, name, score) values (1, 'Ada', 1.5)")).single() !is StatementResult.Failed)

        val (columns, rows) = read()
        assertEquals(1, rows.size)
        val update = buildDml(dialect, DbKind.CASSANDRA, people, columns, listOf("id"), listOf(RowEdit(rows[0], mapOf("name" to CellValue.Text("Grace"), "score" to CellValue.Text("2.5")))), emptyList(), emptyList())
        s.execute(update).getOrThrow()
        val insert = buildDml(dialect, DbKind.CASSANDRA, people, columns, listOf("id"), emptyList(), listOf(mapOf("id" to CellValue.Text("2"), "name" to CellValue.Text("Hopper"), "active" to CellValue.Text("true"), "born" to CellValue.Text("1906-12-09"))), emptyList())
        s.execute(insert).getOrThrow()
        assertEquals(setOf("Grace", "Hopper"), read().second.map { it[columns.indexOfFirst { c -> c.name == "name" }] }.toSet())

        val (c2, r2) = read()
        s.execute(buildDml(dialect, DbKind.CASSANDRA, people, c2, listOf("id"), emptyList(), emptyList(), listOf(r2.first { it[c2.indexOfFirst { c -> c.name == "id" }] == 2 }))).getOrThrow()
        assertEquals(1, read().second.size)
        assertFalse(s.autoCommit.not())      // always auto-commit: there are no transactions
    }
}
