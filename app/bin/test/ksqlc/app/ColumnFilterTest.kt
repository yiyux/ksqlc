package ksqlc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
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
class ColumnFilterTest {
    @get:Rule val rule = createComposeRule()

    private fun tab(): ResultTab {
        val cursor = object : RowCursor { override val exhausted = true; override fun fetch(n: Int) = emptyList<List<Any?>>(); override fun close() {} }
        val t = ResultTab("t", StatementResult.Rows("select", 1, listOf(Column("id", "INTEGER"), Column("grupo", "TEXT")), cursor))
        t.rows.addAll(listOf(listOf(10, "a"), listOf(2, "b"), listOf(3, "a"), listOf(4, null)))
        t.exhausted = true
        return t
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun distinctValuesAreCountedAndSortedNaturally() {
        val t = tab()
        assertEquals(listOf<Pair<String?, Int>>("2" to 1, "3" to 1, "4" to 1, "10" to 1), t.distinct(0))   // numeric, not alphabetical
        assertEquals(listOf<Pair<String?, Int>>("a" to 2, "b" to 1, null to 1), t.distinct(1))            // NULL last
    }

    @Test fun checkingValuesKeepsOnlyThoseRowsAndTheHeaderBoxTogglesAll() {
        val t = tab()
        rule.setContent { KsqlcTheme(ThemeMode.DARK) { Box(Modifier.size(600.dp, 400.dp)) { Grid(t, {}, Modifier.size(600.dp, 400.dp)) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("filter-btn:1").performClick()
        rule.waitUntil { exists("colfilter") }
        rule.onNodeWithTag("colfilter-item:0").performClick()           // value "a"
        rule.waitForIdle()
        assertEquals(setOf<String?>("a"), t.filters[1])
        assertEquals(2, (0..3).count { exists("row:$it") })
        rule.onNodeWithTag("rows-pill").assert(hasText("2 de 4 filas"))

        // a second value widens it; searching narrows the list; the header box selects what is listed
        rule.onNodeWithTag("colfilter-item:1").performClick()
        rule.waitForIdle()
        assertEquals(setOf<String?>("a", "b"), t.filters[1])
        rule.onNodeWithTag("colfilter-search").performTextInput("zzz")
        rule.waitForIdle()
        assertEquals(false, exists("colfilter-item:0"))
        rule.onNodeWithTag("colfilter-search").performTextReplacement("")
        rule.onNodeWithTag("colfilter-all").performClick()               // all were not selected (NULL missing): selects everything
        rule.waitForIdle()
        assertEquals(setOf<String?>("a", "b", null), t.filters[1])
        rule.onNodeWithTag("colfilter-all").performClick()               // everything selected: clears the filter
        rule.waitForIdle()
        assertEquals(false, 1 in t.filters)
        assertEquals(4, (0..3).count { exists("row:$it") })
    }

    @Test fun snapshotOfThePopup() {
        val t = tab()
        t.filters[1] = setOf("a")
        rule.setContent { KsqlcTheme(ThemeMode.LIGHT) { Box(Modifier.size(500.dp, 420.dp)) { Grid(t, {}, Modifier.size(500.dp, 420.dp)) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("filter-btn:1").performClick()
        rule.waitUntil { exists("colfilter") }
        rule.waitForIdle()
        rule.onAllNodes(isRoot())[0].savePng("column-filter-grid")
        rule.onAllNodes(isRoot())[1].savePng("column-filter-popup")   // a popup is its own root
    }
}
