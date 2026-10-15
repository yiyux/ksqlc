package ksqlc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
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
class GridTest {
    @get:Rule val rule = createComposeRule()

    private fun tab(columns: Int): ResultTab {
        val cursor = object : RowCursor { override val exhausted = true; override fun fetch(n: Int) = emptyList<List<Any?>>(); override fun close() {} }
        val cols = List(columns) { Column("c$it", "INTEGER") }
        return ResultTab("t", StatementResult.Rows("select", 1, cols, cursor)).also { it.rows.add(List(columns) { i -> i }); it.exhausted = true }
    }

    private fun show(columns: Int) {
        val t = tab(columns)
        rule.setContent { KsqlcTheme(ThemeMode.DARK) { Box(Modifier.size(600.dp, 300.dp)) { Grid(t, {}, Modifier.size(600.dp, 300.dp)) } } }
        rule.waitForIdle()
    }

    @Test fun horizontalScrollbarAppearsOnlyWhenColumnsOverflow() {
        show(3)
        assertEquals(0, rule.onAllNodesWithTag("grid-hscroll").fetchSemanticsNodes().size)
        rule.onNodeWithTag("rows-pill")
    }

    @Test fun hoveringAColumnNameShowsItsTypeAfterADelay() {
        rule.mainClock.autoAdvance = false
        val t = tab(2)
        rule.setContent { KsqlcTheme(ThemeMode.DARK) { Box(Modifier.size(600.dp, 300.dp)) { Grid(t, {}, Modifier.size(600.dp, 300.dp)) } } }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0, rule.onAllNodesWithTag("tip").fetchSemanticsNodes().size)
        rule.onNodeWithText("c1").performMouseInput { moveTo(center) }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("tip").assertExists().assert(hasAnyDescendant(hasText("c1: integer")))
        rule.onNodeWithText("c1", useUnmergedTree = true).performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(500f, 250f)) }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(0, rule.onAllNodesWithTag("tip").fetchSemanticsNodes().size)
    }

    @Test fun horizontalScrollbarAppearsWithManyColumns() {
        show(12)
        rule.onNodeWithTag("grid-hscroll").assertExists()
        rule.snapshot("grid-wide")
    }
}

