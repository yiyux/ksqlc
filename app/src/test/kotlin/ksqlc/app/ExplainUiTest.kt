package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanTextTest {
    @Test fun oneColumnIsPlainLinesAndSeveralAreAligned() {
        assertEquals("Seq Scan on t\n  Filter: (id = 1)", planText(listOf("QUERY PLAN"), listOf(listOf("Seq Scan on t"), listOf("  Filter: (id = 1)"))))
        assertEquals("id  detail\n1   SCAN t\n12  NULL", planText(listOf("id", "detail"), listOf(listOf(1, "SCAN t"), listOf(12, null))))
    }
}

@OptIn(ExperimentalTestApi::class)
class ExplainUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-x"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-xs")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }

    @Test fun explainShowsAPlanTabAndAnalyzeIsRefusedWhereUnsupported() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        val sql = "create table t(id integer primary key, v text)"
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.explorer != null }

        state.onEditorChange(TextFieldValue("select * from t where id = 1;", TextRange(5)))
        rule.onNodeWithTag("explain").performClick()
        until { !state.running && state.tabs.size == 1 }
        assertTrue(state.tabs[0].plan)
        repeat(10) { rule.mainClock.advanceTimeByFrame() }
        assertEquals("Plan", state.tabs[0].title)
        until { rule.onAllNodesWithTag("plan-view").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("explain-analyze").performClick()
        until { "no está disponible" in state.status }
    }
}
