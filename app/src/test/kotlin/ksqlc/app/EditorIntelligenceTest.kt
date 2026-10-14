package ksqlc.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.sql.Severity
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The editor's assistance (completion, comments, formatting, go-to, problems) against a real SQLite catalog, through the real window. */
@OptIn(ExperimentalTestApi::class)
class EditorIntelligenceTest {
    @get:Rule val rule = createComposeRule()
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ei"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ei-settings")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout (status='${state.status}')" }
            Thread.sleep(40)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }

    private fun frames() = repeat(3) { rule.mainClock.advanceTimeByFrame() }
    private fun put(text: String, caret: Int = text.length) = state.onEditorChange(TextFieldValue(text, TextRange(caret)))
    private fun editorField() = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("editor")))
    private fun typeChars(s: String) = s.forEach { editorField().performTextInput(it.toString()); frames() }
    private fun withMod(mods: List<Key>, key: Key) = editorField().performKeyInput {
        mods.forEach { keyDown(it) }; pressKey(key); mods.reversed().forEach { keyUp(it) }
    }

    /** Opens the window on a fresh SQLite database that already has two tables, and waits for the catalog. */
    private fun ready() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ei", ".db").also { it.toFile().deleteOnExit() }.toString()
        put("create table customers(id integer primary key, name text); create table orders(id integer primary key, customer_id integer, total real)")
        state.runScript()
        until { !state.running && state.catalog?.complete == true }
        rule.onNodeWithTag("editor").performClick(); frames()
    }

    @Test fun completionListsTablesAfterFromAndEnterInsertsTheChoice() {
        ready()
        put("select * from ")
        editorField().performClick(); frames()
        put("select * from ")
        typeChars("cu")
        until { rule.onAllNodesWithTag("completion").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("completion:0").assertTextContains("customers", substring = true)
        editorField().performKeyInput { pressKey(Key.Enter) }; frames()
        assertEquals("select * from customers", state.editor.text)
        assertEquals(0, rule.onAllNodesWithTag("completion").fetchSemanticsNodes().size)
    }

    @Test fun dotAfterAnAliasListsItsColumnsOnceTheyAreLoaded() {
        ready()
        put("select c from customers c", caret = 8)
        editorField().performClick(); frames()
        put("select c from customers c", caret = 8)
        typeChars(".")
        until { rule.onAllNodesWithTag("completion").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("completion:0").assertTextContains("id", substring = true)
        rule.onNodeWithTag("completion:1").assertTextContains("name", substring = true)
        editorField().performKeyInput { pressKey(Key.Escape) }; frames()
        assertEquals(0, rule.onAllNodesWithTag("completion").fetchSemanticsNodes().size)
    }

    @Test fun commandSlashCommentsTheLineAndUndoRestoresIt() {
        ready()
        put("select 1")
        withMod(listOf(Key.MetaLeft), Key.Slash); frames()
        assertEquals("-- select 1", state.editor.text)
        withMod(listOf(Key.MetaLeft), Key.Z); frames()
        assertEquals("select 1", state.editor.text)
    }

    @Test fun formatKeyReformatsTheScript() {
        ready()
        put("select a,b from t where x=1")
        withMod(listOf(Key.MetaLeft, Key.AltLeft), Key.L); frames()
        assertEquals("SELECT\n    a,\n    b\nFROM t\nWHERE x = 1", state.editor.text)
        withMod(listOf(Key.MetaLeft), Key.Z); frames()
        assertEquals("select a,b from t where x=1", state.editor.text)
    }

    @Test fun commandBSelectsTheTableInTheExplorer() {
        ready()
        put("select * from customers", caret = 16)
        editorField().performClick(); frames()
        put("select * from customers", caret = 16)
        withMod(listOf(Key.MetaLeft), Key.B)
        until { state.explorerSelectedId != null }
        assertEquals("ns:.main/Tablas/customers", state.explorerSelectedId)
        rule.onNodeWithTag("tree:ns:.main/Tablas/customers").assertExists()
    }

    @Test fun commandBOnAColumnSelectsItInTheTable() {
        ready()
        put("select c.name from customers c", caret = 11)
        editorField().performClick(); frames()
        put("select c.name from customers c", caret = 11)
        until { state.catalog?.columns(ksqlc.sql.TableRef("main", "customers")) != null }
        withMod(listOf(Key.MetaLeft), Key.B)
        until { state.explorerSelectedId?.endsWith("/cols/name") == true }
        assertEquals("ns:.main/Tablas/customers/cols/name", state.explorerSelectedId)
    }

    @Test fun unknownTablesAreUnderlinedAndExplainedInTheStatusBar() {
        ready()
        put("select * from nada", caret = 16)
        until { state.diagnostics.isNotEmpty() }
        val d = state.diagnostics.single()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("nada", state.editor.text.substring(d.start, d.end))
        until { rule.onAllNodesWithTag("problem").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("problem").assertTextContains("Tabla desconocida: nada", substring = true)
        put("select * from customers")
        until { state.diagnostics.isEmpty() }
    }

    @Test fun enterKeepsTheIndentation() {
        ready()
        put("    select 1")
        editorField().performClick(); frames()
        put("    select 1")
        editorField().performKeyInput { pressKey(Key.Enter) }; frames()
        assertEquals("    select 1\n    ", state.editor.text)
    }
}
