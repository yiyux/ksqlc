package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.JdbcUrl
import ksqlc.drivers.dialect.SqlServerDialect
import ksqlc.drivers.openConnection
import ksqlc.drivers.runAtomically
import ksqlc.drivers.runStepwise
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mssqlserver.MSSQLServerContainer
import kotlin.test.*

/** The T-SQL of [SqlServerDialect.alterTable] applied to a real SQL Server 2022, and the table read back. */
class TableAlterSqlServerTest {
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            fx = fixture(DbKind.MSSQL, JdbcUrl.build(DbKind.MSSQL, c.host, c.getMappedPort(1433), "", false), c.username, c.password, listOf("create schema at"))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun obj(name: String) = DbObject(Namespace(null, "at"), name, DbObjectKind.TABLE)
    private fun model(name: String) = Introspector(fx.ds, fx.password).use { it.tableModel(obj(name))!! }
    private fun statements(name: String, f: (TableModel) -> TableModel): List<String> {
        val before = model(name)
        return SqlServerDialect.alterTable(obj(name), before, f(before))
    }
    /** Applies the change in one transaction and reads the table back under [as]. */
    private fun apply(name: String, readAs: String = name, f: (TableModel) -> TableModel): TableModel {
        val sql = statements(name, f)
        assertNull(runAtomically(fx.ds, fx.password, sql), sql.joinToString("\n"))
        return model(readAs)
    }
    private fun scalar(sql: String): String? = openConnection(fx.ds, fx.password).use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } } }

    @BeforeTest fun fresh() {
        val r = runStepwise(fx.ds, fx.password, listOf(
            "drop table if exists at.t", "drop table if exists at.t_new", "drop table if exists at.t2", "drop table if exists at.parent",
            "create table at.parent(id int not null primary key)",
            "create table at.t(id int identity(1,1) not null, name nvarchar(50) not null constraint df_name default (N'x'), parent_id int null, price int null, " +
                "qty int not null constraint df_qty default (1), constraint pk_t primary key clustered (id), constraint uq_name unique (name), " +
                "constraint fk_parent foreign key (parent_id) references at.parent(id), constraint ck_price check (price > 0))",
            "create index ix_price on at.t(price)",
            "create index ix_qty_f on at.t(qty) where qty > 0",
            "insert into at.parent values (1)",
            "insert into at.t(name, parent_id, price, qty) values (N'a', 1, 5, 2), (N'b', 1, 6, 3)",
            "create table at.t2(a int, b int)",
        ))
        assertNull(r.error)
    }

    @Test fun aLoadedModelProducesNoStatements() {
        for (t in listOf("t", "parent", "t2")) assertEquals(emptyList(), statements(t) { it }, t)
    }

    @Test fun appliesColumnAndConstraintChangesAndReadsBackWithNoDifference() {
        val m = apply("t2") { m ->
            m.copy(
                comment = "tabla t2",
                columns = listOf(
                    m.columns[0].copy(name = "uid", notNull = true, type = "bigint"),
                    m.columns[1],
                    ColumnModel(null, "label", "nvarchar(30)", default = "N'n'", comment = "etiqueta"),
                    ColumnModel(null, "ref", "int"),
                    ColumnModel(null, "dbl", "int", generated = "[b]*(2)"),
                ),
                keys = listOf(KeyModel(null, "pk_t2", true, listOf("uid"))),
                indexes = listOf(IndexModel(null, "ix_label", false, "nonclustered", listOf(IndexColumn("label", order = "DESC")), listOf("b"), "[b] IS NOT NULL")),
                checks = listOf(CheckModel(null, "ck_uid", "[uid] > 0")),
                foreignKeys = listOf(ForeignKeyModel(null, "fk_ref", listOf("ref"), Namespace(null, "at"), "parent", listOf("id"), onDelete = "SET NULL")),
            )
        }
        assertEquals("tabla t2", m.comment)
        assertEquals(listOf("uid", "b", "label", "ref", "dbl"), m.columns.map { it.name })
        assertEquals("bigint", m.columns[0].type); assertTrue(m.columns[0].notNull)
        assertEquals("etiqueta", m.columns[2].comment); assertEquals("N'n'", m.columns[2].default)
        assertEquals("[b]*(2)", m.columns[4].generated); assertTrue(m.columns[4].generatedStored)
        assertEquals(listOf("pk_t2"), m.keys.map { it.name })
        assertEquals("SET NULL", m.foreignKeys.single().onDelete)
        val ix = m.indexes.single { it.name == "ix_label" }
        assertEquals("DESC", ix.columns[0].order); assertEquals(listOf("b"), ix.include)
        assertEquals("[uid]>(0)", m.checks.single().expression)
        assertEquals(emptyList(), statements("t2") { it })      // what was applied reads back with no difference
    }

    @Test fun renamingColumnsUsedByAnIndexACheckAFilterAndADefaultKeepsTheData() {
        val m = apply("t") { m ->
            m.copy(
                columns = m.columns.map { when (it.name) { "price" -> it.copy(name = "cost"); "qty" -> it.copy(name = "amount"); else -> it } },
                indexes = m.indexes.map { ix -> ix.copy(columns = ix.columns.map { c -> c.copy(name = when (c.name) { "price" -> "cost"; "qty" -> "amount"; else -> c.name }) }) },
            )
        }
        assertEquals("11", scalar("select sum(cost) from at.t"))
        assertEquals("5", scalar("select sum(amount) from at.t"))
        assertEquals("[cost]>(0)", m.checks.single().expression)
        assertTrue(m.indexes.single { it.name == "ix_qty_f" }.condition!!.contains("[amount]"))
        assertEquals("1", m.columns.single { it.name == "amount" }.default?.removeSurrounding("(", ")"))
        assertEquals(emptyList(), statements("t") { it })
    }

    @Test fun changingTypesOfColumnsThatKeysIndexesChecksAndDefaultsUseKeepsTheData() {
        val m = apply("t") { m ->
            m.copy(columns = m.columns.map { when (it.name) { "price" -> it.copy(type = "bigint"); "qty" -> it.copy(type = "bigint"); "name" -> it.copy(type = "nvarchar(80)"); else -> it } })
        }
        assertEquals(listOf("nvarchar(80)", "bigint", "bigint"), listOf("name", "price", "qty").map { n -> m.columns.single { it.name == n }.type })
        assertEquals("11", scalar("select sum(price) from at.t"))
        assertEquals(listOf("uq_name"), m.keys.filter { !it.primary }.map { it.name })
        assertEquals(setOf("ix_price", "ix_qty_f"), m.indexes.map { it.name }.toSet())
        assertEquals(listOf("ck_price"), m.checks.map { it.name })
        assertEquals("1", m.columns.single { it.name == "qty" }.default?.removeSurrounding("(", ")"))
        assertEquals(emptyList(), statements("t") { it })
    }

    @Test fun droppingColumnsWithDefaultsIndexesChecksAndForeignKeys() {
        val m = apply("t") { m ->
            m.copy(
                columns = m.columns.filter { it.name !in setOf("qty", "price", "parent_id") },
                indexes = emptyList(), checks = emptyList(), foreignKeys = emptyList(),
            )
        }
        assertEquals(listOf("id", "name"), m.columns.map { it.name })
        assertEquals(0, scalar("select count(*) from sys.default_constraints where parent_object_id = object_id('at.t') and name = 'df_qty'")!!.toInt())
        assertEquals(emptyList(), statements("t") { it })
    }

    @Test fun changingAddingAndRemovingDefaults() {
        val m = apply("t") { m ->
            m.copy(columns = m.columns.map { when (it.name) { "name" -> it.copy(default = "N'y'"); "price" -> it.copy(default = "7"); "qty" -> it.copy(default = null); else -> it } })
        }
        assertEquals("N'y'", m.columns.single { it.name == "name" }.default)
        assertEquals("(7)", m.columns.single { it.name == "price" }.default)
        assertNull(m.columns.single { it.name == "qty" }.default)
        assertEquals(emptyList(), statements("t") { it })
    }

    @Test fun renamingTheTableItsKeysItsForeignKeyItsCheckAndItsIndex() {
        val m = apply("t", readAs = "t_new") { m ->
            m.copy(
                name = "t_new",
                keys = m.keys.map { it.copy(name = it.name + "_n") },
                foreignKeys = m.foreignKeys.map { it.copy(name = "fk_n") },
                checks = m.checks.map { it.copy(name = "ck_n") },
                indexes = m.indexes.map { if (it.name == "ix_price") it.copy(name = "ix_n") else it },
            )
        }
        assertEquals(listOf("pk_t_n", "uq_name_n"), m.keys.map { it.name })
        assertEquals("fk_n", m.foreignKeys.single().name); assertEquals("ck_n", m.checks.single().name)
        assertEquals(setOf("ix_n", "ix_qty_f"), m.indexes.map { it.name }.toSet())
        assertEquals("2", scalar("select count(*) from at.t_new"))
    }

    @Test fun changingWhetherTheKeyIsClusteredAndReplacingAKey() {
        val m = apply("t") { m -> m.copy(keys = listOf(m.keys[0].copy(clustered = false), m.keys[1].copy(columns = listOf("name", "price")))) }
        assertEquals(false, m.keys[0].clustered)
        assertEquals(listOf("name", "price"), m.keys[1].columns)
        assertEquals(emptyList(), statements("t") { it })
    }

    @Test fun commentsAreAddedUpdatedAndDropped() {
        apply("t") { m -> m.copy(comment = "uno", columns = m.columns.map { if (it.name == "name") it.copy(comment = "nombre") else it }) }
        val m = apply("t") { m -> m.copy(comment = "dos", columns = m.columns.map { if (it.name == "name") it.copy(comment = "nombre 2") else it }) }
        assertEquals("dos", m.comment); assertEquals("nombre 2", m.columns.single { it.name == "name" }.comment)
        val none = apply("t") { m -> m.copy(comment = null, columns = m.columns.map { it.copy(comment = null) }) }
        assertNull(none.comment); assertTrue(none.columns.all { it.comment == null })
    }

    @Test fun aTableLockedByAnotherTransactionFailsByTimeoutInsteadOfHanging() {
        val sql = statements("t") { m -> m.copy(columns = m.columns + ColumnModel(null, "extra", "int")) }
        openConnection(fx.ds, fx.password).use { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("update at.t set price = price where id = 1") }
            val started = System.nanoTime()
            val error = runAtomically(fx.ds, fx.password, SqlServerDialect.modifyCaps.prelude + sql)
            other.rollback()
            assertNotNull(error); assertTrue(error.contains("Lock request time out", ignoreCase = true), error)
            assertTrue((System.nanoTime() - started) / 1_000_000_000 < 30, "the lock timeout of the prelude did not apply")
        }
        assertNull(model("t").columns.find { it.name == "extra" })
    }

    @Test fun aFailureInTheMiddleLeavesNothingApplied() {
        val before = model("t")
        val sql = statements("t") { m -> m.copy(columns = m.columns + ColumnModel(null, "extra", "int"), checks = m.checks + CheckModel(null, "ck_bad", "[price] < 0")) }
        val error = runAtomically(fx.ds, fx.password, sql)
        assertNotNull(error); assertTrue(error.contains("ck_bad"), error)
        assertEquals(before, model("t"))
    }
}
