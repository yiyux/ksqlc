package ksqlc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ksqlc.app.ui.KsqlcTheme
import ksqlc.core.Column
import ksqlc.core.RowCursor
import ksqlc.core.StatementResult
import ksqlc.storage.ThemeMode
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ColumnSortTest {
    @get:Rule val rule = createComposeRule()

    private fun tab(): ResultTab {
        val cursor = object : RowCursor { override val exhausted = true; override fun fetch(n: Int) = emptyList<List<Any?>>(); override fun close() {} }
        val t = ResultTab("t", StatementResult.Rows("select", 1, listOf(Column("id", "INTEGER"), Column("name", "TEXT")), cursor))
        t.rows.addAll(listOf(listOf(10, "b"), listOf(2, null), listOf(3, "a")))
        t.exhausted = true
        return t
    }
    private fun order(): List<Int> = rule.onAllNodes(hasTestTagPrefix("row:")).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }.map { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("row:").toInt() }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test fun compareCellsOrdersNullsFirstAndNumbersByValue() {
        assertEquals(listOf<Any?>(null, 2, 3, 10), listOf<Any?>(10, null, 3, 2).sortedWith(::compareCells))
        assertEquals(listOf<Any?>("a", "B", "c"), listOf<Any?>("c", "a", "B").sortedWith(::compareCells))
        assertEquals(0, compareCells(1, 1L))
        assertEquals(-1, compareCells(2.5, 10).coerceIn(-1, 1))
    }

    @Test fun theArrowCyclesAscendingDescendingAndNone() {
        val t = tab()
        rule.setContent { KsqlcTheme(ThemeMode.DARK) { Box(Modifier.size(600.dp, 400.dp)) { Grid(t, {}, Modifier.size(600.dp, 400.dp)) } } }
        rule.waitForIdle()
        assertEquals(listOf(0, 1, 2), order())
        rule.onNodeWithTag("sort-btn:0").performClick(); rule.waitForIdle()
        assertEquals(0 to true, t.sort)
        assertEquals(listOf(1, 2, 0), order())            // 2, 3, 10
        rule.onNodeWithTag("sort-btn:0").performClick(); rule.waitForIdle()
        assertEquals(0 to false, t.sort)
        assertEquals(listOf(0, 2, 1), order())            // 10, 3, 2
        rule.onNodeWithTag("sort-btn:1").performClick(); rule.waitForIdle()   // another column starts ascending, NULL first
        assertEquals(1 to true, t.sort)
        assertEquals(listOf(1, 2, 0), order())            // null, a, b
        rule.onNodeWithTag("sort-btn:1").performClick(); rule.onNodeWithTag("sort-btn:1").performClick(); rule.waitForIdle()
        assertEquals(null, t.sort)
        assertEquals(listOf(0, 1, 2), order())
    }

    @Test fun anOwnerThatSortsGetsTheRequestInsteadOfALocalOrder() {
        val t = tab(); val asked = mutableListOf<Pair<Int, Boolean?>>()
        rule.setContent { KsqlcTheme(ThemeMode.DARK) { Box(Modifier.size(600.dp, 400.dp)) { Grid(t, {}, Modifier.size(600.dp, 400.dp), onSort = { j, asc -> asked += j to asc }) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("sort-btn:1").performClick(); rule.waitForIdle()
        assertEquals(listOf<Pair<Int, Boolean?>>(1 to true), asked)
        assertEquals(listOf(0, 1, 2), order())             // rows untouched: the owner reloads
    }

    @Test fun snapshotOfTheHeader() {
        val t = tab(); t.sort = 1 to false; t.filters[0] = setOf("2")
        rule.setContent { KsqlcTheme(ThemeMode.LIGHT) { Box(Modifier.size(300.dp, 120.dp)) { Grid(t, {}, Modifier.size(300.dp, 120.dp)) } } }
        rule.waitForIdle()
        rule.snapshot("column-header-light")
    }
}
