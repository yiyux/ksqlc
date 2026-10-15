package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
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
class HistoryUiTest {
    @get:Rule val rule = createComposeRule()

    private val history = HistoryStore(Files.createTempDirectory("ksqlc-ui-hist"))
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-h"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-hs")),
        history,
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

    private fun setEditor(sql: String) = state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))

    @Test fun recordsRunsSearchesAndInsertsIntoTheConsole() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("select 1 as uno; select * from no_existe;")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && history.load("unsaved").size == 2 }

        val saved = history.load("unsaved")
        assertEquals(listOf("select * from no_existe;".trimEnd(';'), "select 1 as uno").sorted(), saved.map { it.sql.trim().trimEnd(';') }.sorted())
        assertEquals(listOf(true, false), saved.map { it.ok }.sortedDescending())

        setEditor("")
        rule.onNodeWithTag("history-open").performClick()
        until { rule.onAllNodesWithTag("history-dialog").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("history:1").assertExists()

        rule.onNodeWithTag("history-search").performTextInput("uno")
        until { rule.onAllNodesWithTag("history:1").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("history:0").assertExists()

        rule.onNodeWithTag("history-insert").performClick()
        until { rule.onAllNodesWithTag("history-dialog").fetchSemanticsNodes().isEmpty() }
        assertTrue("select 1 as uno" in state.editor.text)
    }
}
