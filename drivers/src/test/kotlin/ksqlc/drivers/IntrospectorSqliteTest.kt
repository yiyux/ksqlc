package ksqlc.drivers

import ksqlc.core.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class IntrospectorSqliteTest {
    private val file = Files.createTempFile("ksqlc", ".db").also { it.toFile().deleteOnExit() }
    private val ds = DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite:$file")
    private val main = Namespace(null, "main")

    init {
        DriverManager.getConnection(ds.url).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("create table a(id integer primary key, name text not null default 'x')")
                st.executeUpdate("create table \"we\"\"ird\"(id int)")
                st.executeUpdate("create table b(id int, a_id int references a(id), unique(id))")
                st.executeUpdate("create index b_aid on b(a_id)")
                st.executeUpdate("create view v as select * from a")
            }
        }
    }

    private fun intro() = Introspector(ds, null)

    @Test fun namespacesListMainOnly() {
        intro().use { assertEquals(listOf(main), it.namespaces()) }
    }

    @Test fun objectsAreTablesAndViewsSortedWithoutInternalOnes() {
        intro().use {
            val objs = it.objects(main)
            assertEquals(listOf("a", "b", "v", "we\"ird"), objs.map { o -> o.name })
            assertEquals(DbObjectKind.VIEW, objs.single { o -> o.name == "v" }.kind)
            assertEquals(DbObjectKind.TABLE, objs.single { o -> o.name == "a" }.kind)
        }
    }

    @Test fun detailsHaveColumnsPrimaryKeyIndexesAndForeignKeys() {
        intro().use { i ->
            val a = i.details(DbObject(main, "a", DbObjectKind.TABLE))
            assertEquals(listOf("id", "name"), a.columns.map { it.name })
            assertEquals(listOf("id"), a.primaryKey)
            assertFalse(a.columns[1].nullable)
            assertEquals("'x'", a.columns[1].default)

            val b = i.details(DbObject(main, "b", DbObjectKind.TABLE))
            assertEquals(setOf("b_aid"), b.indexes.map { it.name }.filter { n -> !n.startsWith("sqlite_autoindex") }.toSet())
            assertTrue(b.indexes.any { it.unique && it.columns == listOf("id") })
            val fk = b.foreignKeys.single()
            assertEquals(listOf("a_id"), fk.columns)
            assertEquals("a", fk.refTable)
            assertEquals(listOf("id"), fk.refColumns)
        }
    }

    @Test fun viewDetailsOnlyListColumns() {
        intro().use { i ->
            val v = i.details(DbObject(main, "v", DbObjectKind.VIEW))
            assertEquals(listOf("id", "name"), v.columns.map { it.name })
            assertTrue(v.indexes.isEmpty() && v.foreignKeys.isEmpty())
        }
    }

    @Test fun ddlComesFromSqliteMasterWithIndexes() {
        intro().use { i ->
            val ddl = i.ddl(DbObject(main, "b", DbObjectKind.TABLE))
            assertContains(ddl, "CREATE TABLE b")
            assertContains(ddl, "create index b_aid", ignoreCase = true)
            assertTrue(ddl.endsWith(";"))
            assertContains(i.ddl(DbObject(main, "v", DbObjectKind.VIEW)), "create view v", ignoreCase = true)
        }
    }

    @Test fun selectAllQuotesOddNames() {
        intro().use { i ->
            val o = DbObject(main, "we\"ird", DbObjectKind.TABLE)
            assertEquals("select * from \"main\".\"we\"\"ird\"", i.dialect.selectAll(o))
            // the quoted statement must be valid SQL too
            Session(ds, null).use { s ->
                assertIs<StatementResult.Rows>(s.runScript(listOf(i.dialect.selectAll(o))).single())
            }
        }
    }

    @Test fun unknownObjectDdlFails() {
        intro().use { i ->
            assertFailsWith<IllegalStateException> { i.ddl(DbObject(main, "nope", DbObjectKind.TABLE)) }
        }
    }

    @Test fun sharedMemoryDatabaseIsVisibleToConsoleAndTree() {
        val mem = DataSource("m", "m", DbKind.SQLITE, JdbcUrl.build(DbKind.SQLITE, "", null, "", false))
        Session(mem, null).use { console ->
            console.runScript(listOf("create table shared_t(id int)"))
            Introspector(mem, null).use { tree ->
                assertEquals(listOf("shared_t"), tree.objects(main).map { it.name })
            }
        }
    }
}
