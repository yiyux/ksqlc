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
class ExportUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-e"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-es")),
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
    private fun untilTag(tag: String) = until { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun setEditor(sql: String) = state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))

    @Test fun exportsATableTabAndAConsoleResultToFiles() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("create table people(id integer primary key, name text); insert into people values (1, 'Ada'), (2, 'Grace, \"G\"');")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }

        // a table tab: the filter is applied to the export too
        val people = ksqlc.core.DbObject(ksqlc.core.Namespace(null, "main"), "people", ksqlc.core.DbObjectKind.TABLE)
        state.openData(people)
        until { state.dataTabs.size == 1 }
        state.dataTabs[0].where = "id = 2"
        rule.onNodeWithTag("export-open").performClick()
        untilTag("export-dialog")
        val csv = Files.createTempFile("ksqlc-export", ".csv")
        rule.onNodeWithTag("export-path").performTextInput(csv.toString())
        rule.onNodeWithTag("export-run").performClick()
        until { rule.onAllNodesWithTag("export-dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals("id,name\n2,\"Grace, \"\"G\"\"\"\n", Files.readString(csv))
        assertTrue("1 filas exportadas" in state.status, state.status)

        // a console result, as JSON
        state.activeEditor = CONSOLE_TAB
        setEditor("select id, name from people order by id")
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        rule.onNodeWithTag("export-format:2").performClick()
        val json = Files.createTempFile("ksqlc-export", ".json")
        rule.onNodeWithTag("export-path").performTextInput(json.toString())
        rule.onNodeWithTag("export-run").performClick()
        until { rule.onAllNodesWithTag("export-dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals("[\n  {\"id\": 1, \"name\": \"Ada\"},\n  {\"id\": 2, \"name\": \"Grace, \\\"G\\\"\"}\n]\n", Files.readString(json))
    }

    @Test fun missingPathShowsAnError() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("select 1 as a")
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        rule.onNodeWithTag("export-run").performClick()
        untilTag("export-error")
    }
}
