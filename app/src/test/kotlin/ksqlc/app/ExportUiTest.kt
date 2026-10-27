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
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}' export=${state.exportJob?.let { "running=${it.running} progress=${it.progress} err=${it.error} path=${it.path}" }})" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
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
        until { state.exportJob!!.sample != null }
        assertEquals("2,\"Grace, \"\"G\"\"\"\n", state.exportJob!!.previewText())   // no header by default
        rule.onNodeWithTag("export-header").performClick()
        rule.onNodeWithTag("export-path").performTextReplacement(csv.toString())
        until { state.exportJob!!.previewText().startsWith("id,name\n") }
        rule.onNodeWithTag("export-file").performClick()
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
        until { state.exportJob!!.sample != null }
        rule.onNodeWithTag("export-format").performClick()
        until { exists("export-format:2") }
        rule.onNodeWithTag("export-format:2").performClick()
        until { !exists("export-format:2") }
        rule.mainClock.advanceTimeBy(800)
        // the file name follows the format until the user types one
        assertTrue(state.exportJob!!.path.endsWith("Resultado_1.json"), state.exportJob!!.path)
        val json = Files.createTempFile("ksqlc-export", ".json")
        rule.onNodeWithTag("export-path").performTextReplacement(json.toString())
        until { state.exportJob!!.path == json.toString() }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("export-file").assertIsEnabled().performClick()
        until { state.exportJob == null || state.exportJob!!.error != null }
        assertEquals(null, state.exportJob?.error)
        assertEquals("[\n  {\"id\": 1, \"name\": \"Ada\"},\n  {\"id\": 2, \"name\": \"Grace, \\\"G\\\"\"}\n]\n", Files.readString(json))
    }

    @Test fun exportsEveryRowOfAResultThatWasCutAtThePageLimit() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        val total = PAGE * 2 + 300
        // the first result is not the last statement of the script, so the console keeps only its first page
        setEditor("with recursive n(i) as (select 1 union all select i + 1 from n where i < $total) select i from n; select 1 as z")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.tabs.size == 2 }
        assertEquals(PAGE, state.tabs[0].rows.size)
        state.selectedTab = 0
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        val csv = Files.createTempFile("ksqlc-export", ".csv")
        rule.onNodeWithTag("export-path").performTextReplacement(csv.toString())
        until { state.exportJob!!.path == csv.toString() }
        rule.onNodeWithTag("export-file").assertIsEnabled().performClick()
        until { state.exportJob == null || state.exportJob!!.error != null }
        assertEquals(null, state.exportJob?.error)
        assertEquals(total, Files.readAllLines(csv).size)
    }

    @Test fun aResultFromAStatementThatWritesIsNeverRunAgain() {
        rule.setContent { App(state) }
        val db = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        state.form.database = db
        setEditor("create table t(id integer primary key, v text)")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }
        setEditor("insert into t(v) values ('a') returning id, v")
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        assertTrue(!exists("export-all"), "no debe ofrecer volver a ejecutar una sentencia que escribe")
        val csv = Files.createTempFile("ksqlc-export", ".csv")
        rule.onNodeWithTag("export-path").performTextReplacement(csv.toString())
        until { state.exportJob!!.path == csv.toString() }
        rule.onNodeWithTag("export-file").assertIsEnabled().performClick()
        until { state.exportJob == null || state.exportJob!!.error != null }
        assertEquals(null, state.exportJob?.error)
        assertEquals(listOf("1,a"), Files.readAllLines(csv))
        val rows = java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { st -> st.executeQuery("select count(*) from t").use { it.next(); it.getInt(1) } } }
        assertEquals(1, rows)
    }

    @Test fun canExportOnlyTheLoadedRows() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("with recursive n(i) as (select 1 union all select i + 1 from n where i < ${PAGE * 2}) select i from n; select 1 as z")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.tabs.size == 2 }
        state.selectedTab = 0
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        val csv = Files.createTempFile("ksqlc-export", ".csv")
        rule.onNodeWithTag("export-path").performTextReplacement(csv.toString())
        rule.onNodeWithTag("export-loaded").performClick()
        until { state.exportJob!!.path == csv.toString() && !state.exportJob!!.allRows }
        rule.onNodeWithTag("export-file").assertIsEnabled().performClick()
        until { state.exportJob == null || state.exportJob!!.error != null }
        assertEquals(PAGE, Files.readAllLines(csv).size)
    }

    private fun fakeRequest(write: WriteFn) = ExportRequest("grande", "origen", "grande", { it }, null, sample = { ExportSample(listOf("a"), listOf(listOf(1))) }, write = write)

    private fun startFake(write: WriteFn): java.nio.file.Path {
        val out = Files.createTempFile("ksqlc-export", ".csv")
        state.openExport(fakeRequest(write))
        untilTag("export-dialog")
        rule.onNodeWithTag("export-path").performTextReplacement(out.toString())
        until { state.exportJob!!.path == out.toString() }
        rule.onNodeWithTag("export-file").assertIsEnabled().performClick()
        untilTag("export-indicator")
        return out
    }

    @Test fun aRunningExportKeepsGoingWhenTheDialogIsHiddenAndShowsAnIndicator() {
        rule.setContent { App(state) }
        val gate = java.util.concurrent.CountDownLatch(1)
        startFake { w, _, progress ->
            val out = w(listOf("a")); out.header(); progress(1)
            gate.await(20, java.util.concurrent.TimeUnit.SECONDS)
            out.row(listOf(1)); out.finish(); out.count
        }
        rule.onNodeWithTag("export-hide").performClick()
        until { !exists("export-dialog") }
        assertTrue(state.exportJob?.running == true)
        assertTrue(exists("export-indicator"))
        gate.countDown()
        until { state.exportJob == null }
        assertTrue("1 filas exportadas" in state.status, state.status)
        assertTrue(!exists("export-indicator"))
    }

    @Test fun theIndicatorCancelsARunningExport() {
        rule.setContent { App(state) }
        startFake { w, stop, progress ->
            val out = w(listOf("a")); out.header(); progress(0)
            while (!stop()) Thread.sleep(10)
            out.finish(); out.count
        }
        rule.onNodeWithTag("export-hide").performClick()
        until { !exists("export-dialog") }
        rule.onNodeWithTag("export-indicator-cancel").performClick()
        until { state.exportJob == null }
        assertTrue("(cancelado)" in state.status, state.status)
    }

    @Test fun missingPathShowsAnError() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("select 1 as a")
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        rule.onNodeWithTag("export-result").performClick()
        untilTag("export-dialog")
        rule.onNodeWithTag("export-path").performTextReplacement("")
        rule.onNodeWithTag("export-file").performClick()
        untilTag("export-error")
    }
}
