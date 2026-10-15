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

@OptIn(ExperimentalTestApi::class)
class NotebookUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-n"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ns")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun frames(n: Int = 5) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        frames(2)
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun eachStatementIsACellWithItsResultUnderneath() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        state.onEditorChange(TextFieldValue("select 1 as a;\n\nselect 2 as b, 3 as c;\n\nselect * from nope", TextRange(0)))
        rule.onNodeWithTag("notebook-toggle").performClick()
        until { exists("notebook") }
        assertTrue(exists("nb-cell:0") && exists("nb-cell:1") && exists("nb-cell:2"))

        // one cell
        rule.onNodeWithTag("nb-run:1").performClick()
        until { state.cellRuns[1]?.tab != null }
        assertEquals(listOf("2", "3"), state.cellRuns[1]!!.tab!!.rows.single().map { it.toString() })
        until { exists("nb-result:1") }
        assertEquals(null, state.cellRuns[0])      // the others did not run
        assertEquals(0, state.tabs.size)           // and nothing went to the result tabs

        // all of them, stopping at the failing one
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.cellRuns[2] != null && !state.cellRuns[2]!!.ok }
        assertEquals("1", state.cellRuns[0]!!.tab!!.rows.single().single().toString())
        assertTrue(state.cellRuns[2]!!.message!!.contains("nope"), state.cellRuns[2]!!.message)

        // editing a cell edits the text, and its old result is marked stale
        rule.onNodeWithTag("nb-text:0").performTextReplacement("select 10 as a")
        until { state.editor.text.startsWith("select 10 as a;") }
        assertTrue(state.editor.text.contains("select 2 as b"), state.editor.text)
        assertEquals("select 1 as a", state.cellRuns[0]!!.source)

        // typing: spaces and line breaks at the end of a cell stay in the field while the text is split (which trims them)
        rule.onNodeWithTag("nb-text:1").performTextReplacement("select 2 ")
        frames(); rule.onNodeWithTag("nb-text:1").assertTextEquals("select 2 ")
        rule.onNodeWithTag("nb-text:1").performTextReplacement("select 2 as b,\n")
        frames(); rule.onNodeWithTag("nb-text:1").assertTextEquals("select 2 as b,\n")
        rule.onNodeWithTag("nb-text:1").performTextReplacement("select 2 as b, 3 as c")
        until { state.editor.text.contains("select 2 as b, 3 as c;") }

        // delete and add
        rule.onNodeWithTag("nb-delete:0").performClick()
        until { state.cells().size == 2 }
        assertEquals("select 2 as b, 3 as c", state.cellRuns[0]!!.source)      // results follow their cell
        state.addCell()
        until { state.cells().size == 3 }
        assertEquals("select 1", state.cells().last().text(state.editor.text))
    }
}
