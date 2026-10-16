package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.JdbcUrl
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mssqlserver.MSSQLServerContainer
import kotlin.test.*

/** Reading the editable model of a SQL Server 2022 table. */
class TableModelSqlServerTest {
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            fx = fixture(DbKind.MSSQL, JdbcUrl.build(DbKind.MSSQL, c.host, c.getMappedPort(1433), "", false), c.username, c.password, listOf(
                "create schema mt",
                "create table mt.parent(id int not null primary key)",
                "create table mt.t(" +
                    "id int identity(1,1) not null, " +
                    "a nvarchar(50) collate Latin1_General_CS_AS not null constraint df_a default (N'x'), " +
                    "b decimal(10,2) null constraint df_b default (0), " +
                    "c int not null, " +
                    "total as (b * 2) persisted, " +
                    "v as (c + 1), " +
                    "parent_id int null, " +
                    "constraint pk_t primary key nonclustered (id), " +
                    "constraint uq_t unique (a, c), " +
                    "constraint fk_t_parent foreign key (parent_id) references mt.parent(id) on delete cascade on update set null, " +
                    "constraint ck_t check (c > 0))",
                "create clustered index cx_t on mt.t(c desc)",
                "create unique index ux_t on mt.t(b) include (a) where b is not null",
                "create nonclustered index ix_t on mt.t(parent_id, c desc)",
                "create nonclustered columnstore index ncci on mt.t(c)",
                "exec sp_addextendedproperty 'MS_Description', 'Tabla t', 'SCHEMA', 'mt', 'TABLE', 't'",
                "exec sp_addextendedproperty 'MS_Description', 'columna a', 'SCHEMA', 'mt', 'TABLE', 't', 'COLUMN', 'a'",
                "create table mt.hist(id int not null primary key clustered, valid_from datetime2 generated always as row start not null, " +
                    "valid_to datetime2 generated always as row end not null, period for system_time (valid_from, valid_to)) " +
                    "with (system_versioning = on (history_table = mt.hist_history))",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun load(table: String): TableModel = Introspector(fx.ds, fx.password).use { it.tableModel(DbObject(Namespace(null, "mt"), table, DbObjectKind.TABLE)) }!!
    private val model by lazy { load("t") }

    @Test fun readsTheTableAndItsColumns() {
        assertEquals("t", model.name)
        assertEquals("Tabla t", model.comment)
        assertEquals(listOf("id", "a", "b", "c", "total", "v", "parent_id"), model.columns.map { it.name })
        assertTrue(model.columns.all { it.origin == it.name })
        val col = model.columns.associateBy { it.name }
        assertEquals("int", col.getValue("id").type)
        assertNotNull(col.getValue("id").identity)
        assertTrue(col.getValue("id").notNull)
        // a column's own collation travels in its type, because alter column would reset it
        assertEquals("nvarchar(50) collate Latin1_General_CS_AS", col.getValue("a").type)
        assertEquals("N'x'", col.getValue("a").default)
        assertEquals("columna a", col.getValue("a").comment)
        assertEquals("decimal(10,2)", col.getValue("b").type)
        assertFalse(col.getValue("b").notNull)
        assertEquals("(0)", col.getValue("b").default)
        assertEquals("[b]*(2)", col.getValue("total").generated)
        assertTrue(col.getValue("total").generatedStored)
        assertEquals("[c]+(1)", col.getValue("v").generated)
        assertFalse(col.getValue("v").generatedStored)
        assertNull(col.getValue("total").default)
        assertNull(col.getValue("c").comment)
    }

    @Test fun readsKeysWithTheirClusteredFlag() {
        assertEquals(listOf("pk_t", "uq_t"), model.keys.map { it.name })
        val pk = model.keys[0]
        assertTrue(pk.primary); assertEquals(listOf("id"), pk.columns); assertEquals(false, pk.clustered)
        val uq = model.keys[1]
        assertFalse(uq.primary); assertEquals(listOf("a", "c"), uq.columns); assertEquals(false, uq.clustered)
    }

    @Test fun readsIndexesAndLeavesColumnstoreAlone() {
        assertEquals(listOf("cx_t", "ix_t", "ux_t"), model.indexes.map { it.name })
        val cx = model.indexes[0]
        assertEquals("clustered", cx.method); assertFalse(cx.unique)
        assertEquals(listOf(IndexColumn("c", order = "DESC")), cx.columns)
        val ix = model.indexes[1]
        assertEquals("nonclustered", ix.method)
        assertEquals(listOf(IndexColumn("parent_id"), IndexColumn("c", order = "DESC")), ix.columns)
        val ux = model.indexes[2]
        assertTrue(ux.unique); assertEquals(listOf("a"), ux.include)
        assertEquals(listOf(IndexColumn("b")), ux.columns)
        assertTrue(ux.condition!!.contains("[b] IS NOT NULL", ignoreCase = true), ux.condition)
        assertEquals(listOf("ncci"), model.skipped)
    }

    @Test fun readsForeignKeysAndChecks() {
        val fk = model.foreignKeys.single()
        assertEquals("fk_t_parent", fk.name)
        assertEquals(listOf("parent_id"), fk.columns)
        assertEquals(Namespace(null, "mt"), fk.refNs); assertEquals("parent", fk.refTable); assertEquals(listOf("id"), fk.refColumns)
        assertEquals("CASCADE", fk.onDelete); assertEquals("SET NULL", fk.onUpdate)
        val ck = model.checks.single()
        assertEquals("ck_t", ck.name); assertEquals("[c]>(0)", ck.expression)
    }

    @Test fun aTableWithoutCommentsOrExtrasReadsPlain() {
        val p = load("parent")
        assertNull(p.comment)
        assertEquals(listOf("id"), p.columns.map { it.name })
        assertEquals(1, p.keys.size); assertTrue(p.keys[0].primary); assertEquals(true, p.keys[0].clustered)
        assertTrue(p.indexes.isEmpty() && p.foreignKeys.isEmpty() && p.checks.isEmpty() && p.skipped.isEmpty())
    }

    @Test fun aTemporalTableIsRefusedWithAReason() {
        val e = assertFailsWith<IllegalStateException> { load("hist") }
        assertTrue(e.message!!.contains("temporal"), e.message)
    }
}
