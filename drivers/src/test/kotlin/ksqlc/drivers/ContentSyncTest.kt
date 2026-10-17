package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ContentSyncTest {
    private val cols = listOf("id", "v")
    // left: 1 same, 2 differs in v, 3 only left; right: 4 only right
    private val diff = diffRows(
        cols, listOf("id"),
        left = listOf(listOf(1, "a"), listOf(2, "b2"), listOf(3, "c")),
        right = listOf(listOf(1, "a"), listOf(2, "b"), listOf(4, "d")),
    )
    private val mysql = DbObject(Namespace("app", null), "t", DbObjectKind.TABLE)
    private fun my(d: ContentDiff = diff, insert: Boolean = true, update: Boolean = true, delete: Boolean = true, skip: Set<String> = emptySet()) =
        syncStatements(Dialect.of(DbKind.MYSQL), DbKind.MYSQL, mysql, d, insert, update, delete, skip)

    @Test fun deletesThenUpdatesThenInsertsWithTheKeyOfTheRightRow() {
        val s = my()
        assertEquals(listOf(
            "delete from `app`.`t` where `id` = ?",
            "update `app`.`t` set `v` = ? where `id` = ?",
            "insert into `app`.`t` (`id`, `v`) values (?, ?)",
        ), s.map { it.sql })
        assertEquals(listOf(listOf(4), listOf("b2", 2), listOf(3, "c")), s.map { it.params })
        assertEquals(listOf(
            "delete from `app`.`t` where `id` = 4",
            "update `app`.`t` set `v` = 'b2' where `id` = 2",
            "insert into `app`.`t` (`id`, `v`) values (3, 'c')",
        ), s.map { it.preview })
    }

    @Test fun eachKindCanBeLeftOut() {
        assertEquals(listOf("delete"), my(insert = false, update = false).map { it.sql.substringBefore(" ") })
        assertEquals(listOf("update"), my(insert = false, delete = false).map { it.sql.substringBefore(" ") })
        assertEquals(listOf("insert"), my(update = false, delete = false).map { it.sql.substringBefore(" ") })
        assertEquals(emptyList(), my(insert = false, update = false, delete = false))
    }

    @Test fun anUpdateSetsOnlyTheCellsThatChangeAndNeverTheKey() {
        val d = diffRows(
            listOf("id", "a", "b", "c"), listOf("id"),
            left = listOf(listOf(1, "x", 2, "z")), right = listOf(listOf(1, "x", 3, "y")),
        )
        val u = my(d).single()
        assertEquals("update `app`.`t` set `b` = ?, `c` = ? where `id` = ?", u.sql)
        assertEquals(listOf<Any?>(2, "z", 1), u.params)
    }

    @Test fun aCompositeKeyIsInTheWhereOfEveryChange() {
        val d = diffRows(
            listOf("a", "b", "v"), listOf("a", "b"),
            left = listOf(listOf(1, 2, "x")), right = listOf(listOf(1, 2, "y"), listOf(3, 4, "z")),
        )
        assertEquals(
            listOf("delete from `app`.`t` where `a` = ? and `b` = ?", "update `app`.`t` set `v` = ? where `a` = ? and `b` = ?"),
            my(d).map { it.sql },
        )
    }

    @Test fun nullsQuotesAndBinariesAreBoundNotWrittenIntoTheSql() {
        val d = diffRows(
            listOf("id", "v", "b"), listOf("id"),
            left = listOf(listOf(1, "it's", byteArrayOf(1, 2)), listOf(2, null, null)), right = emptyList(),
        )
        val s = my(d)
        assertEquals("insert into `app`.`t` (`id`, `v`, `b`) values (?, ?, ?)", s[0].sql)
        assertEquals("insert into `app`.`t` (`id`, `v`, `b`) values (1, 'it''s', <2 bytes>)", s[0].preview)
        assertEquals("insert into `app`.`t` (`id`, `v`, `b`) values (2, NULL, NULL)", s[1].preview)
        assertContentEquals(byteArrayOf(1, 2), s[0].params[2] as ByteArray)
    }

    @Test fun skippedColumnsAreLeftOutOfInsertsAndUpdates() {
        val d = diffRows(listOf("id", "v", "gen"), listOf("id"), left = listOf(listOf(1, "a", 10), listOf(2, "b", 20)), right = listOf(listOf(1, "a", 99)))
        // gen is generated: its difference is no reason to update, and it is not inserted
        val s = my(d, skip = setOf("gen"))
        assertEquals(listOf("insert into `app`.`t` (`id`, `v`) values (?, ?)"), s.map { it.sql })
        assertEquals(emptyList(), my(diffRows(listOf("id", "gen"), listOf("id"), listOf(listOf(1, 1)), listOf(listOf(1, 2))), skip = setOf("gen")))
    }

    @Test fun theRightTablesSpellingOfTheColumnsIsWhatIsQuoted() {
        val s = my(diff.copy(rightColumns = listOf("ID", "V")), insert = false, delete = false)
        assertEquals("update `app`.`t` set `V` = ? where `ID` = ?", s.single().sql)
    }

    @Test fun postgresGetsTextAndUnknownTypesUntyped() {
        val d = diffRows(
            listOf("id", "u", "t"), listOf("id"),
            left = listOf(listOf(1, UUID.fromString("00000000-0000-0000-0000-000000000001"), "x")), right = emptyList(),
        )
        val s = syncStatements(Dialect.of(DbKind.POSTGRES), DbKind.POSTGRES, DbObject(Namespace(null, "app"), "t", DbObjectKind.TABLE), d, true, true, true).single()
        assertEquals("insert into \"app\".\"t\" (\"id\", \"u\", \"t\") values (?, ?, ?)", s.sql)
        assertEquals(listOf(1, UntypedParam("00000000-0000-0000-0000-000000000001"), UntypedParam("x")), s.params)
    }

    @Test fun sqlServerGivesEachInsertIntoAnIdentityTableItsOwnIdentityInsert() {
        val t = DbObject(Namespace(null, "app"), "t", DbObjectKind.TABLE)
        fun ms(identity: Set<String>, d: ContentDiff = diff) = syncStatements(Dialect.of(DbKind.MSSQL), DbKind.MSSQL, t, d, true, false, false, identity = identity).map { it.sql }
        // each insert carries its own set: a prepared statement has a scope of its own, where an earlier `set` is lost
        assertEquals(listOf("set identity_insert [app].[t] on; insert into [app].[t] ([id], [v]) values (?, ?); set identity_insert [app].[t] off"), ms(setOf("id")))
        assertEquals(listOf("insert into [app].[t] ([id], [v]) values (?, ?)"), ms(emptySet()))
        assertEquals(emptyList(), ms(setOf("id"), diffRows(cols, listOf("id"), listOf(listOf(1, "a")), listOf(listOf(1, "a")))))   // no inserts, no set
    }

    // --- syncProblem --------------------------------------------------------------------------------

    @Test fun theProblemsThatForbidASync() {
        assertNull(syncProblem(diff, partial = false, readOnly = false))
        assertTrue(syncProblem(diffRows(cols, listOf("id"), emptyList(), emptyList()), false, false)!!.contains("No hay diferencias"))
        assertTrue(syncProblem(diff, partial = false, readOnly = true)!!.contains("solo lectura"))
        assertTrue(syncProblem(diff, partial = true, readOnly = false)!!.contains("canceló"))
        assertTrue(syncProblem(diff.copy(truncated = true), false, false)!!.contains("más filas"))
        assertTrue(syncProblem(diff.copy(rightKey = emptyList()), false, false)!!.contains("clave primaria"))
        assertTrue(syncProblem(diff.copy(rightKey = listOf("v")), false, false)!!.contains("clave primaria"))
        assertNull(syncProblem(diff.copy(rightKey = listOf("ID")), false, false))        // the same key, spelled differently
        assertTrue(syncProblem(diff, false, false, limit = 2)!!.contains("2 como mucho"))
    }

    // --- with real tables -----------------------------------------------------------------------------

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-sync", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(ds(f), null).use { s -> s.runScript(sql.toList()).forEach { assertFalse(it is StatementResult.Failed, it.toString()) } }
        return f
    }
    private fun ds(file: String) = DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$file")
    private val t = DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE)
    private fun compare(a: String, b: String): ContentDiff =
        Introspector(ds(a), null).use { l -> Introspector(ds(b), null).use { r ->
            Session(ds(a), null).use { ls -> Session(ds(b), null).use { rs -> diffTableContent(l, ls, t, r, rs, t) } }
        } }
    private fun left() = db(
        "create table t(id integer primary key, v text, n real, b blob)",
        "insert into t values (1,'a',1.5,x'01'),(2,'b2',2,x'02'),(3,'c',null,null)",
    )
    private fun right() = db(
        "create table t(id integer primary key, v text, n real, b blob)",
        "insert into t values (1,'a',1.5,x'01'),(2,'b',2,x'09'),(4,'d',4,null)",
    )

    @Test fun applyingTheScriptLeavesTheTablesEqual() {
        val a = left(); val b = right()
        val d = compare(a, b)
        val script = syncStatements(Dialect.of(DbKind.SQLITE), DbKind.SQLITE, t, d, insert = true, update = true, delete = true)
        assertEquals(3, script.size)
        Session(ds(b), null).use { assertEquals(3L, it.execute(script).getOrThrow()) }
        val again = compare(a, b)
        assertEquals(emptyList(), again.rows)
        assertEquals(3, again.same)
    }

    @Test fun withoutTheDeleteKindTheRowOnlyOnTheRightStays() {
        val a = left(); val b = right()
        val script = syncStatements(Dialect.of(DbKind.SQLITE), DbKind.SQLITE, t, compare(a, b), insert = true, update = true, delete = false)
        Session(ds(b), null).use { it.execute(script).getOrThrow() }
        assertEquals(listOf(RowStatus.ONLY_RIGHT), compare(a, b).rows.map { it.status })
    }

    @Test fun aFailureInTheMiddleLeavesTheRightTableAsItWas() {
        val a = left()
        val b = db("create table t(id integer primary key, v text, n real, b blob, u text unique)", "insert into t values (1,'a',1.5,x'01','k'),(2,'b',2,x'09','k2')")
        // inserting row 3 (u null) is fine, row 2's update is fine: make the second insert collide
        val d = compare(a, b)
        val script = syncStatements(Dialect.of(DbKind.SQLITE), DbKind.SQLITE, t, d, true, true, true) +
            DmlStatement("insert into \"main\".\"t\" (id, v, u) values (?, ?, ?)", listOf(10, "x", "k"), "")   // u = 'k' already exists
        val before = compare(a, b)
        assertTrue(Session(ds(b), null).use { it.execute(script) }.isFailure)
        assertEquals(before.rows.map { it.status to it.key }, compare(a, b).rows.map { it.status to it.key })
    }
}
