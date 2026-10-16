package ksqlc.drivers

import ksqlc.core.*
import java.nio.file.Files
import kotlin.test.*

class ContentDiffTest {
    private val cols = listOf("id", "v")

    @Test fun classifiesRowsAsOnlyLeftOnlyRightDifferentOrSame() {
        val d = diffRows(
            cols, listOf("id"),
            left = listOf(listOf(1, "a"), listOf(2, "b"), listOf(3, "c")),
            right = listOf(listOf(1, "a"), listOf(2, "B"), listOf(4, "d")),
        )
        assertEquals(1, d.same)
        assertEquals(listOf(RowStatus.DIFFERENT, RowStatus.ONLY_LEFT, RowStatus.ONLY_RIGHT), d.rows.map { it.status })
        assertEquals(setOf(1), d.rows[0].changed)
        assertEquals(listOf<Any?>(3), d.rows[1].key)
    }

    @Test fun numbersCompareByValueAndBlobsByContent() {
        val d = diffRows(
            listOf("id", "n", "b"), listOf("id"),
            left = listOf(listOf(1, 1, byteArrayOf(1, 2)), listOf(2, 5, byteArrayOf(1))),
            right = listOf(listOf(1L, 1.0, byteArrayOf(1, 2)), listOf(2L, 5.0, byteArrayOf(9))),
        )
        assertEquals(1, d.same)
        assertEquals(setOf(2), d.rows.single().changed)
    }

    @Test fun compositeKeyAndNulls() {
        val d = diffRows(
            listOf("a", "b", "v"), listOf("a", "b"),
            left = listOf(listOf(1, 1, null), listOf(1, 2, "x")),
            right = listOf(listOf(1, 1, null), listOf(1, 2, null)),
        )
        assertEquals(1, d.same)
        assertEquals(setOf(2), d.rows.single().changed)
    }

    @Test fun duplicateKeyOnTheRightFails() {
        assertFailsWith<IllegalStateException> { diffRows(cols, listOf("id"), emptyList(), listOf(listOf(1, "a"), listOf(1, "b"))) }
    }

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-cdiff", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { s -> s.runScript(sql.toList()).forEach { assertFalse(it is StatementResult.Failed, it.toString()) } }
        return f
    }
    private fun ds(file: String) = DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$file")
    private val t = DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE)

    private fun compare(a: String, b: String, limit: Int = 1000): ContentDiff =
        Introspector(ds(a), null).use { l -> Introspector(ds(b), null).use { r ->
            Session(ds(a), null).use { ls -> Session(ds(b), null).use { rs -> diffTableContent(l, ls, t, r, rs, t, limit) } }
        } }

    @Test fun comparesTwoRealTablesOverTheirCommonColumns() {
        val a = db("create table t(id integer primary key, v text, only_a integer)", "insert into t values (1,'a',0),(2,'b',0),(3,'c',0)")
        val b = db("create table t(id integer primary key, v text, only_b integer)", "insert into t values (1,'a',0),(2,'x',0),(4,'d',0)")
        val d = compare(a, b)
        assertEquals(listOf("id", "v"), d.columns)
        assertEquals(listOf("only_a"), d.onlyLeftColumns)
        assertEquals(listOf("only_b"), d.onlyRightColumns)
        assertEquals(1, d.same)
        assertEquals(setOf(RowStatus.DIFFERENT, RowStatus.ONLY_LEFT, RowStatus.ONLY_RIGHT), d.rows.map { it.status }.toSet())
        assertFalse(d.truncated)
    }

    @Test fun withoutPrimaryKeyFails() {
        val a = db("create table t(id integer, v text)")
        assertTrue(assertFailsWith<IllegalArgumentException> { compare(a, a) }.message!!.contains("clave primaria"))
    }

    @Test fun limitMarksTheResultTruncated() {
        val a = db("create table t(id integer primary key)", "insert into t values (1),(2),(3)")
        assertTrue(compare(a, a, limit = 2).truncated)
        assertFalse(compare(a, a, limit = 3).truncated)
    }
}
