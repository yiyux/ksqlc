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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OverflowTabsUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-o"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-os")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}')" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun tags(prefix: String) = rule.onAllNodes(hasTestTag(prefix).or(SemanticsMatcher("tag starts with $prefix") { n ->
        androidx.compose.ui.semantics.SemanticsProperties.TestTag in n.config && n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("$prefix:")
    })).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }

    @Test fun hiddenTabsAreListedAndPickable() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        val ddl = (1..25).joinToString("; ") { "create table table_number_$it(id integer primary key)" }
        state.onEditorChange(TextFieldValue(ddl, TextRange(ddl.length)))
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }
        for (n in 1..25) {
            state.openData(DbObject(Namespace(null, "main"), "table_number_$n", DbObjectKind.TABLE))
            until { state.dataTabs.size == n }
        }
        assertTrue(rule.onAllNodesWithTag("tabs-overflow").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("tabs-overflow").performClick()
        until { tags("tabs-hidden").isNotEmpty() }
        rule.onAllNodes(isRoot())[0].savePng("overflow-tabs"); rule.onAllNodes(isRoot())[1].savePng("overflow-menu")
        val first = tags("tabs-hidden").first().substringAfter(':').toInt()
        val expected = if (first == 0) CONSOLE_TAB else first - 1      // entry 0 is the console (scrolled away by the newest tab)
        rule.onNodeWithTag("tabs-hidden:$first").performClick()
        until { state.activeEditor == expected }
        repeat(10) { rule.mainClock.advanceTimeByFrame() }      // the scroll that brings it into view
        // the picked tab is now in view, so it no longer shows in the list
        rule.onNodeWithTag("tabs-overflow").performClick()
        until { tags("tabs-hidden").isNotEmpty() }
        assertTrue(tags("tabs-hidden").none { it.substringAfter(':').toInt() == first })
    }
}
