package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.nio.file.Files
import kotlin.test.*

class CopyTableTest {
    private val sqlite = Dialect.of(DbKind.SQLITE)
    private val t = DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE)
    private val copy = DbObject(Namespace(null, "main"), "t_copy", DbObjectKind.TABLE)

    private fun ds(file: String) = DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$file")
    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-copy", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(ds(f), null).use { s -> s.runScript(sql.toList()).forEach { assertFalse(it is StatementResult.Failed, it.toString()) } }
        return f
    }
    private fun source() = db(
        "create table t(id integer primary key, v text not null, n real, b blob)",
        "insert into t values (1,'a',1.5,x'01'),(2,'b',2,x'02'),(3,'c',null,null)",
    )
    private fun rows(file: String, table: String) = Session(ds(file), null).use { s ->
        (s.runScript(listOf("select id, v, n from $table order by id")).single() as StatementResult.Rows).cursor.use { it.fetch(100) }
    }

    private fun run(from: String, to: String, create: String?, cols: CopyColumns, cancelled: () -> Boolean = { false }) =
        Session(ds(from), null).use { a -> Session(ds(to), null).use { b ->
            copyRows(a, sqlite, t, cols.from, b, sqlite, DbKind.SQLITE, copy, cols.to, emptySet(), create, cancelled)
        } }

    private fun details(file: String) = Introspector(ds(file), null).use { it.details(t) }

    @Test fun createsTheTableWithTypesNotNullAndKeyAndCopiesEveryRow() {
        val a = source(); val b = db()
        val d = details(a)
        val create = sqlite.createTable(copy, d)
        assertContains(create, "create table \"main\".\"t_copy\"")
        assertContains(create, "primary key (\"id\")")
        assertContains(create, "\"v\" TEXT not null")
        assertEquals(3L, run(a, b, create, copyColumns(d, null)).getOrThrow())
        assertEquals(rows(a, "t"), rows(b, "t_copy"))
    }

    @Test fun addsToAnExistingTableMatchingColumnsByNameAndLeavesTheOthersAlone() {
        val a = source()
        val b = db("create table t_copy(ID integer primary key, v text, extra text default 'x')", "insert into t_copy(ID, v) values (9,'z')")
        val cols = copyColumns(details(a), Introspector(ds(b), null).use { it.details(copy) })
        assertEquals(listOf("id", "v"), cols.from.take(2))
        assertEquals(listOf("ID", "v"), cols.to)
        assertEquals(3L, run(a, b, null, cols).getOrThrow())
        assertEquals(4, Session(ds(b), null).use { s -> (s.runScript(listOf("select count(*) from t_copy")).single() as StatementResult.Rows).cursor.use { it.fetch(1)[0][0] as Number }.toInt() })
    }

    @Test fun aFailureLeavesNoNewTableAndNoRowsInAnExistingOne() {
        val a = source()
        val d = details(a)
        val fresh = db()
        assertTrue(run(a, fresh, sqlite.createTable(copy, d), copyColumns(d, null), cancelled = { true }).isFailure)
        val tables = Session(ds(fresh), null).use { s -> (s.runScript(listOf("select name from sqlite_master")).single() as StatementResult.Rows).cursor.use { it.fetch(10) } }
        assertEquals(emptyList(), tables)

        // the key repeats on the target, so the second row fails and the first must not stay
        val dup = db("create table t_copy(id integer primary key, v text)", "insert into t_copy values (2,'old')")
        assertTrue(run(a, dup, null, copyColumns(d, Introspector(ds(dup), null).use { it.details(copy) })).isFailure)
        assertEquals(listOf("old"), Session(ds(dup), null).use { s -> (s.runScript(listOf("select v from t_copy")).single() as StatementResult.Rows).cursor.use { c -> c.fetch(10).map { it[0] } } })
    }

    @Test fun problemsAreNamedBeforeAnythingRuns() {
        val ok = CopyColumns(listOf("id"), listOf("id"))
        assertNull(copyProblem(DbKind.POSTGRES, DbKind.POSTGRES, false, targetExists = false, ok))
        assertNull(copyProblem(DbKind.MYSQL, DbKind.MARIADB, false, targetExists = false, ok))
        assertNull(copyProblem(DbKind.POSTGRES, DbKind.MSSQL, false, targetExists = true, ok))
        assertNotNull(copyProblem(DbKind.POSTGRES, DbKind.MSSQL, false, targetExists = false, ok))
        assertNotNull(copyProblem(DbKind.POSTGRES, DbKind.POSTGRES, true, targetExists = true, ok))
        assertNotNull(copyProblem(DbKind.CASSANDRA, DbKind.POSTGRES, false, targetExists = true, ok))
        assertNotNull(copyProblem(DbKind.POSTGRES, DbKind.POSTGRES, false, targetExists = true, CopyColumns(emptyList(), emptyList())))
    }

    @Test fun anIdentityColumnOnSqlServerSetsIdentityInsertInTheSameBatch() {
        val sql = copyInsertSql(Dialect.of(DbKind.MSSQL), DbKind.MSSQL, t, listOf("id", "v"), setOf("id"))
        assertTrue(sql.startsWith("set identity_insert") && sql.endsWith(" off"), sql)
        assertFalse(copyInsertSql(Dialect.of(DbKind.MSSQL), DbKind.MSSQL, t, listOf("v"), setOf("id")).contains("identity_insert"))
    }
}
