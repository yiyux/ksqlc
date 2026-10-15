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
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ParamDialogUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-p"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ps")),
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
    private fun runCurrent(sql: String) {
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
    }

    @Test fun parametersAreAskedForThenFilledIn() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        runCurrent("select :a + :b as total, :a as a, ':a' as text")
        until { exists("param-dialog") }
        rule.onNodeWithTag("param-ok").assertIsNotEnabled()
        rule.onNodeWithTag("param:0").performTextReplacement("2")
        rule.onNodeWithTag("param:1").performTextReplacement("3")
        until { state.paramPrompt != null }
        frames(); rule.onNodeWithTag("param-ok").assertIsEnabled().performClick()
        until { !state.running && state.tabs.size == 1 }
        assertEquals(listOf("5", "2", ":a"), state.tabs[0].rows.single().map { it.toString() })

        // the dialog offers the last values again, and Cancelar runs nothing
        runCurrent("select :a")
        until { exists("param-dialog") }
        assertEquals("2", state.paramPrompt!!.initial.single())
        rule.onNodeWithTag("param-cancel").performClick()
        until { !exists("param-dialog") }
        assertNull(state.paramPrompt)
        assertEquals(listOf("5", "2", ":a"), state.tabs[0].rows.single().map { it.toString() })
    }

    @Test fun questionMarksAreAskedOnePerOccurrence() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        runCurrent("select ? as x, ? as y")
        until { exists("param-dialog") }
        assertEquals(listOf("? 1", "? 2"), state.paramPrompt!!.names)
        rule.onNodeWithTag("param:0").performTextReplacement("'a'")
        rule.onNodeWithTag("param:1").performTextReplacement("7")
        until { state.paramPrompt != null }; frames()
        rule.onNodeWithTag("param-ok").performClick()
        until { !state.running && state.tabs.size == 1 }
        assertEquals(listOf("a", "7"), state.tabs[0].rows.single().map { it.toString() })
    }

    @Test fun theDialectDecidesGoBatchesAndQuestionMarks() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        rule.onNodeWithTag("dialect").assertTextContains("Dialecto: auto", substring = true)
        rule.onNodeWithTag("dialect").performClick()
        until { exists("dialect:MSSQL") }
        rule.onNodeWithTag("dialect:MSSQL").performClick()
        until { !exists("dialect:MSSQL") }; rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("dialect").assertTextContains("SQL Server", substring = true)
        state.onEditorChange(TextFieldValue("select 1 as a\nGO\nselect 2 as b", TextRange(5)))
        state.runScript()
        until { !state.running && state.tabs.size == 2 }      // two batches; as plain SQLite text the GO line would be a syntax error

        state.editorDialect = ksqlc.core.DbKind.POSTGRES      // `?` is an operator there, not a parameter
        state.onEditorChange(TextFieldValue("select 1 as q", TextRange(5)))
        assertEquals(null, state.paramPrompt)
    }
}
