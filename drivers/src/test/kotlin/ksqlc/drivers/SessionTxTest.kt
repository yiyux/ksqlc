package ksqlc.drivers

import ksqlc.core.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** Transactions on a real SQLite file, with a second connection to see what has really been confirmed. */
class SessionTxTest {
    private val file = Files.createTempFile("ksqlc-tx", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"
    private fun ds(manual: Boolean) = DataSource("t", "t", DbKind.SQLITE, url, manualTx = manual)

    init { DriverManager.getConnection(url).use { it.createStatement().execute("create table t(id integer primary key, v text)") } }

    private fun visibleRows(): List<String> = DriverManager.getConnection(url).use { c ->
        c.createStatement().executeQuery("select v from t order by id").let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
    private fun ins(v: String) = DmlStatement("insert into t(v) values (?)", listOf(v), "insert into t(v) values ('$v')")

    @Test fun autoModeConfirmsABatchAtOnce() = Session(ds(false), null).use { s ->
        assertEquals(2L, s.execute(listOf(ins("a"), ins("b"))).getOrThrow())
        assertEquals(listOf("a", "b"), visibleRows())
        assertFalse(s.pending)
    }

    @Test fun autoModeBatchIsAllOrNothing() = Session(ds(false), null).use { s ->
        val r = s.execute(listOf(ins("a"), DmlStatement("insert into nope values (1)", emptyList(), "insert into nope values (1)")))
        assertTrue(r.isFailure)
        assertContains(r.exceptionOrNull()!!.message!!, "insert into nope")      // names the culprit
        assertEquals(emptyList(), visibleRows())
    }

    @Test fun manualModeKeepsWorkPendingUntilCommit() = Session(ds(true), null).use { s ->
        assertTrue(s.execute(listOf(ins("a"))).isSuccess)
        assertTrue(s.pending)
        assertEquals(emptyList(), visibleRows())                                  // not confirmed yet
        s.commit()
        assertFalse(s.pending)
        assertEquals(listOf("a"), visibleRows())
    }

    @Test fun rollbackDiscardsPendingWork() = Session(ds(true), null).use { s ->
        s.execute(listOf(ins("a")))
        s.rollback()
        assertFalse(s.pending)
        assertEquals(emptyList(), visibleRows())
    }

    @Test fun aFailureInManualModeKeepsTheEarlierStatements() = Session(ds(true), null).use { s ->
        s.execute(listOf(ins("kept")))
        val bad = s.execute(listOf(ins("lost"), DmlStatement("insert into nope values (1)", emptyList(), "x")))
        assertTrue(bad.isFailure)
        s.commit()
        assertEquals(listOf("kept"), visibleRows())
    }

    @Test fun consoleStatementsMarkManualSessionsPendingButReadsDoNot() = Session(ds(true), null).use { s ->
        s.runScript(listOf("select * from t")); assertFalse(s.pending)
        s.runScript(listOf("insert into t(v) values ('x')")); assertTrue(s.pending)
        s.rollback()
        assertEquals(emptyList(), visibleRows())
    }

    @Test fun switchingToAutoConfirmsFromThenOn() = Session(ds(true), null).use { s ->
        s.runScript(listOf("select 1"))
        s.setAutoCommit(true)
        s.runScript(listOf("insert into t(v) values ('now')"))
        assertEquals(listOf("now"), visibleRows())
        s.setAutoCommit(false)
        s.runScript(listOf("insert into t(v) values ('later')"))
        assertEquals(listOf("now"), visibleRows())
        s.rollback()
    }

    @Test fun commitAndRollbackAreHarmlessInAutoMode() = Session(ds(false), null).use { s ->
        s.runScript(listOf("insert into t(v) values ('x')")); s.commit(); s.rollback()
        assertEquals(listOf("x"), visibleRows())
    }

    @Test fun anOldStoredConnectionWithoutTheFlagIsAuto() = assertFalse(DataSource("i", "n", DbKind.SQLITE, "u").manualTx)
}
