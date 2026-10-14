package ksqlc.drivers

import ksqlc.core.*
import kotlin.concurrent.thread
import kotlin.test.*

class SessionTest {
    private val ds = DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite::memory:")
    private fun session() = Session(ds, null)
    private val numbers = "with recursive c(x) as (select 1 union all select x+1 from c where x<%d) select x from c"

    @Test fun sessionKeepsStateAcrossScriptRuns() = session().use { s ->
        s.runScript(listOf("create table t(a int)", "insert into t values (1),(2)"))
        val r = s.runScript(listOf("select count(*) from t")).single() as StatementResult.Rows
        assertEquals(listOf(listOf<Any?>(2)), r.cursor.fetch(10))
    }

    @Test fun updateCountsAndMultipleResults() = session().use { s ->
        val rs = s.runScript(listOf("create table t(a int)", "insert into t values (1),(2),(3)", "select a from t", "select 'x' as y"))
        assertIs<StatementResult.Updated>(rs[0])
        assertEquals(3, (rs[1] as StatementResult.Updated).count)
        assertEquals(listOf(listOf<Any?>(1), listOf(2), listOf(3)), (rs[2] as StatementResult.Rows).cursor.fetch(10))
        assertEquals(listOf("y"), (rs[3] as StatementResult.Rows).columns.map { it.name })
    }

    @Test fun scriptStopsAtFirstFailure() = session().use { s ->
        val rs = s.runScript(listOf("select 1", "select * from missing", "select 3"))
        assertEquals(2, rs.size)
        assertIs<StatementResult.Rows>(rs[0])
        assertIs<StatementResult.Failed>(rs[1])
        assertContains((rs[1] as StatementResult.Failed).message, "missing")
    }

    @Test fun cursorPaginates() = session().use { s ->
        val r = s.runScript(listOf(numbers.format(25))).single() as StatementResult.Rows
        assertEquals(10, r.cursor.fetch(10).size)
        assertFalse(r.cursor.exhausted)
        assertEquals(10, r.cursor.fetch(10).size)
        assertEquals(5, r.cursor.fetch(10).size)
        assertTrue(r.cursor.exhausted)
        assertTrue(r.cursor.fetch(10).isEmpty())
    }

    @Test fun earlierResultsAreTruncatedNotLost() = session().use { s ->
        val rs = s.runScript(listOf(numbers.format(30), "select 1"), keepRows = 10)
        val first = rs[0] as StatementResult.Rows
        assertTrue(first.truncated)
        assertEquals(10, first.cursor.fetch(500).size)
        assertFalse((rs[1] as StatementResult.Rows).truncated)
    }

    @Test fun cancelInterruptsLongQuery() = session().use { s ->
        var out: List<StatementResult>? = null
        val t = thread { out = s.runScript(listOf(numbers.format(2_000_000_000).replace("select x from c", "select count(*) from c"))) }
        // Keep cancelling until the script ends: the first call may land before the statement exists.
        val deadline = System.nanoTime() + 15_000_000_000
        while (t.isAlive && System.nanoTime() < deadline) { Thread.sleep(200); s.cancel() }
        assertFalse(t.isAlive, "query should stop after cancel")
        val f = out!!.single()
        assertIs<StatementResult.Failed>(f)
        assertTrue(f.cancelled)
    }
}
