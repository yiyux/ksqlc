package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DataSortUiTest {
    @get:Rule val rule = createComposeRule()
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-sort"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-sorts")),
    )
    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun ids(tab: DataTab) = tab.grid.rows.map { it[0].toString().toInt() }

    @Test fun theHeaderArrowOrdersOnTheServerAndAPlainReloadForgetsIt() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        state.onEditorChange(TextFieldValue("create table t(id integer, v text); insert into t values (3,'c'),(1,'a'),(2,'b');", TextRange(0)))
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }
        state.openData(DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE)); until { state.dataTabs.size == 1 }
        val tab = state.dataTabs[0]
        assertEquals(listOf(3, 1, 2), ids(tab))

        rule.onNodeWithTag("sort-btn:0").performClick()                       // ascending
        until { ids(tab) == listOf(1, 2, 3) }
        assertEquals("\"id\" asc", tab.orderBy)
        assertEquals(0 to true, tab.grid.sort)
        rule.onNodeWithTag("sort-btn:0").performClick()                       // descending
        until { ids(tab) == listOf(3, 2, 1) }
        assertEquals(0 to false, tab.grid.sort)
        rule.onNodeWithTag("sort-btn:0").performClick()                       // none
        until { ids(tab) == listOf(3, 1, 2) }
        assertEquals("", tab.orderBy); assertEquals(null, tab.grid.sort)

        // typing an ORDER BY by hand and reloading does not leave a stale arrow
        tab.orderBy = "v desc"; state.applyFilters(tab)
        until { ids(tab) == listOf(3, 2, 1) }
        assertEquals(null, tab.grid.sort)
    }
}
