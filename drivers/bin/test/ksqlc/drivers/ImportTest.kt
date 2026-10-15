package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import kotlin.test.*

class ImportTest {
    private val ds = DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite::memory:")
    private val table = DbObject(Namespace(null, "main"), "people", DbObjectKind.TABLE)
    private val cols = listOf(ColumnInfo("id", "INTEGER"), ColumnInfo("name", "TEXT"), ColumnInfo("score", "REAL"))
    private fun go(s: Session, mapping: List<Int?>, vararg rows: List<String?>, first: Int = 2) =
        importRows(s, Dialect.of(DbKind.SQLITE), DbKind.SQLITE, table, cols, mapping, rows.toList().iterator(), first)
    private fun count(s: Session) = ((s.runScript(listOf("select count(*) from people")).single() as StatementResult.Rows).cursor.fetch(1).single().single() as Number).toInt()

    @Test fun importsMappedColumnsAndTypesTheValues() {
        Session(ds, null).use { s ->
            s.runScript(listOf("create table people(id integer primary key, name text not null default 'x', score real)"))
            // file columns: score, id, (ignored), name
            val r = go(s, listOf(2, 0, null, 1), listOf("1.5", "1", "zz", "Ada"), listOf(null, "2", "zz", "Grace, G."))
            assertEquals(2L, r.getOrThrow())
            val rows = (s.runScript(listOf("select id, name, score from people order by id")).single() as StatementResult.Rows).cursor.fetch(10)
            assertEquals(listOf<List<Any?>>(listOf(1, "Ada", 1.5), listOf(2, "Grace, G.", null)), rows)
        }
    }

    @Test fun aBadValueNamesTheRowAndColumnAndLeavesTheTableUntouched() {
        Session(ds, null).use { s ->
            s.runScript(listOf("create table people(id integer primary key, name text, score real)"))
            val rows = (1..1200).map { listOf<String?>("$it", "n$it", "1") } + listOf(listOf("1201", "n", "abc"))
            val r = importRows(s, Dialect.of(DbKind.SQLITE), DbKind.SQLITE, table, cols, listOf(0, 1, 2), rows.iterator(), 2)
            val e = r.exceptionOrNull()
            assertIs<ImportException>(e)
            assertEquals("fila 1202, columna score: «abc» no es un número", e.message)
            assertEquals(0, count(s))
        }
    }

    @Test fun databaseErrorsRollBackToo_andManualModeKeepsEarlierWork() {
        Session(ds, null).use { s ->
            s.runScript(listOf("create table people(id integer primary key, name text, score real)"))
            val r = go(s, listOf(0, 1), listOf("1", "a"), listOf("1", "dup"))
            assertTrue(r.isFailure)
            assertEquals(0, count(s))
        }
        Session(ds, null, autoCommit = false).use { s ->
            s.runScript(listOf("create table people(id integer primary key, name text, score real)"))
            go(s, listOf(0, 1), listOf("1", "a")).getOrThrow()
            assertTrue(go(s, listOf(0, 1), listOf("2", "b"), listOf("2", "dup")).isFailure)
            assertEquals(1, count(s))   // the failed import is undone, the earlier one stays pending
            assertTrue(s.pending)
        }
    }

    @Test fun refusesAnEmptyOrDuplicatedMapping() {
        Session(ds, null).use { s ->
            assertTrue(go(s, listOf(null, null), listOf("a", "b")).isFailure)
            assertTrue(go(s, listOf(1, 1), listOf("a", "b")).exceptionOrNull()!!.message!!.contains("name"))
        }
    }
}
