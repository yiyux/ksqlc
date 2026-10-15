package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.runAtomically
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

class TableAlterServerTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema at",
                "create table at.parent(id int primary key)",
                """create table at.t(
                     id serial primary key,
                     name text not null default 'x',
                     parent_id int,
                     price numeric(10,2),
                     constraint t_price_ck check (price > 0))""",
                "create index t_name_idx on at.t (name)",
                "create table at.u(id int)",
                "create table at.w(id int, a int unique)",
                "insert into at.t(name, price) values ('a', 1), ('b', 2)",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun obj(name: String) = DbObject(Namespace(null, "at"), name, DbObjectKind.TABLE)
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun model(name: String) = intro().use { it.tableModel(obj(name))!! }
    private fun statements(name: String, f: (TableModel) -> TableModel): Pair<TableModel, List<String>> {
        val before = model(name)
        return before to intro().use { it.dialect.alterTable(obj(name), before, f(before)) }
    }

    @Test fun aLoadedModelProducesNoStatements() {
        for (t in listOf("t", "parent", "u")) assertEquals(emptyList(), statements(t) { it }.second, t)
    }

    @Test fun appliesRenamesTypesDefaultsConstraintsAndIndexesAtOnce() {
        val (_, sql) = statements("u") { m ->
            m.copy(
                comment = "tabla u",
                columns = listOf(
                    m.columns[0].copy(name = "uid", notNull = true, type = "bigint"),
                    ColumnModel(null, "label", "text", default = "'n'", comment = "etiqueta"),
                    ColumnModel(null, "ref", "int"),
                ),
                keys = listOf(KeyModel(null, "u_pkey", true, listOf("uid"))),
                checks = listOf(CheckModel(null, "u_label_ck", "(length(label) > 0)")),
                indexes = listOf(IndexModel(null, "u_label_idx", false, "btree", listOf(IndexColumn("label", order = "DESC")), condition = "(label <> 'z')")),
                foreignKeys = listOf(ForeignKeyModel(null, "u_ref_fk", listOf("ref"), Namespace(null, "at"), "parent", listOf("id"), onDelete = "SET NULL")),
            )
        }
        assertNull(runAtomically(fx.ds, fx.password, sql), sql.joinToString("\n"))
        val m = model("u")
        assertEquals("tabla u", m.comment)
        assertEquals(listOf("uid", "label", "ref"), m.columns.map { it.name })
        assertEquals("bigint", m.columns[0].type); assertTrue(m.columns[0].notNull)
        assertEquals("etiqueta", m.columns[1].comment)
        assertEquals(listOf("u_pkey"), m.keys.map { it.name })
        assertEquals("SET NULL", m.foreignKeys.single().onDelete)
        assertEquals("DESC", m.indexes.single().columns[0].order)
        assertEquals(emptyList(), statements("u") { it }.second)      // what was applied reads back with no difference
    }

    @Test fun renamingAColumnKeepsTheDataTheIndexAndTheCheck() {
        val (_, sql) = statements("t") { m ->
            m.copy(
                columns = m.columns.map { if (it.name == "name") it.copy(name = "title") else it },
                indexes = m.indexes.map { ix -> ix.copy(columns = ix.columns.map { c -> if (c.name == "name") c.copy(name = "title") else c }) },
            )
        }
        assertEquals(1, sql.size, sql.toString())
        assertNull(runAtomically(fx.ds, fx.password, sql))
        val m = model("t")
        assertEquals(listOf("title"), m.indexes.single().columns.map { it.name })
        assertEquals(2, java.sql.DriverManager.getConnection(fx.ds.url, fx.ds.user, fx.password).use { c ->
            c.createStatement().executeQuery("select count(*) from at.t where title is not null").use { it.next(); it.getInt(1) }
        })
    }

    @Test fun aFailureHalfwayLeavesNothingApplied() {
        val before = model("parent")
        val sql = listOf("alter table \"at\".\"parent\" add column extra int", "alter table \"at\".\"parent\" add column extra int")      // the second repeats the name
        val error = runAtomically(fx.ds, fx.password, sql)
        assertNotNull(error); assertTrue(error.contains("extra"), error)
        assertEquals(before.columns.map { it.name }, model("parent").columns.map { it.name })
    }

    @Test fun aUniqueKeyOnADroppedColumnSurvivesWhenANewColumnReusesTheName() {
        val (_, sql) = statements("w") { m -> m.copy(columns = m.columns.filter { it.name != "a" } + ColumnModel(null, "a", "text")) }
        assertNull(runAtomically(fx.ds, fx.password, sql), sql.joinToString("\n"))
        assertEquals(listOf("a"), model("w").keys.single().columns)
    }
}
