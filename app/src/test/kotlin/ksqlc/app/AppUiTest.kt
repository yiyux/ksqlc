package ksqlc.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import ksqlc.storage.ThemeMode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the real window headlessly. Uses temp stores and an in-memory secret store, never the user's own. */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    @get:Rule val rule = createComposeRule()

    private val settingsDir = Files.createTempDirectory("ksqlc-ui-settings")
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui"), InMemorySecretStore()),
        SettingsStore(settingsDir),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    /**
     * Background work (IO threads) runs in real time, but `waitUntil` counts virtual frames and each
     * software-rendered frame is slow, so poll the wall clock and render one frame per poll instead.
     */
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
    private fun frames() = repeat(3) { rule.mainClock.advanceTimeByFrame() }
    /** Lets popup enter/exit animations finish; a closing menu keeps its click-blocking layer until then. */
    private fun settle() = rule.mainClock.advanceTimeBy(800)
    private fun tempDb() = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
    private fun setEditor(sql: String) = state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
    private fun runAll() { rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick() }

    @Test fun runScriptThenBrowseSchemaAndOpenTableData() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("create table customers(id integer primary key, name text); create view v as select * from customers; insert into customers values (1, 'Ada'), (2, 'Grace');")
        runAll()

        // the run succeeds, the explorer connects on its own and keeps the run's status line
        until { !state.running && state.explorer != null }
        assertEquals("3 sentencia(s) ejecutada(s)", state.status)
        untilTag("tree:ns:.main")

        rule.onNodeWithTag("tree:ns:.main").performClick()
        untilTag("tree:ns:.main/Tablas")
        rule.onNodeWithTag("tree:ns:.main/Vistas").assertExists()
        rule.onNodeWithTag("tree:ns:.main/Tablas").performClick()
        untilTag("tree:ns:.main/Tablas/customers")

        // expanding the table lists its columns
        rule.onNodeWithTag("tree:ns:.main/Tablas/customers").performClick()
        untilTag("tree:ns:.main/Tablas/customers/cols")
        rule.onNodeWithTag("tree:ns:.main/Tablas/customers/cols").assertTextContains("Columnas (2)", substring = true)

        // double click opens the data in an editor tab beside the console
        rule.onNodeWithTag("tree:ns:.main/Tablas/customers").performMouseInput { doubleClick() }
        until { state.dataTabs.singleOrNull()?.grid?.rows?.size == 2 }
        assertEquals(0, state.activeEditor)
        rule.onNodeWithTag("etab:0").assertExists()
        rule.onNodeWithTag("etab:console").assertExists()
        rule.onNodeWithText("Ada").assertExists()
        rule.onNodeWithText("Grace").assertExists()

        // the console tab brings the editor back; a second double click reuses the tab
        rule.onNodeWithTag("etab:console").performClick(); frames()
        rule.onNodeWithTag("editor").assertExists()
        rule.onNodeWithTag("tree:ns:.main/Tablas/customers").performMouseInput { doubleClick() }
        frames()
        assertEquals(1, state.dataTabs.size)
        assertEquals(0, state.activeEditor)

        rule.onNodeWithContentDescription("Cerrar customers").performClick(); frames()
        assertTrue(state.dataTabs.isEmpty())
        assertEquals(CONSOLE_TAB, state.activeEditor)
    }

    @Test fun ddlOutsideTheTreeIsPickedUpByAutoRefresh() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("create table one(id int)")
        state.runScript()
        until { state.explorer != null && !state.running }
        rule.onNodeWithTag("tree:ns:.main").performClick()
        untilTag("tree:ns:.main/Tablas")
        rule.onNodeWithTag("tree:ns:.main/Tablas").assertTextContains("Tablas (1)", substring = true)

        setEditor("create table two(id int)")
        state.runScript()
        until { !state.running && state.explorer?.roots?.firstOrNull()?.children?.firstOrNull()?.label == "Tablas (2)" }
        rule.onNodeWithTag("tree:ns:.main/Tablas").assertTextContains("Tablas (2)", substring = true)
    }

    @Test fun filterNarrowsTheTree() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("create table alpha(id int); create table beta(id int)")
        state.runScript()
        until { state.explorer != null && !state.running }
        rule.onNodeWithTag("tree:ns:.main").performClick()
        untilTag("tree:ns:.main/Tablas")
        rule.onNodeWithTag("tree:ns:.main/Tablas").performClick()
        untilTag("tree:ns:.main/Tablas/alpha")
        rule.onNodeWithTag("tree:ns:.main/Tablas/beta").assertExists()

        rule.onNodeWithContentDescription("Filtrar").performClick()
        untilTag("explorer-filter")
        rule.onNodeWithTag("explorer-filter").performTextInput("alp")
        until { rule.onAllNodesWithTag("tree:ns:.main/Tablas/beta").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("tree:ns:.main/Tablas/alpha").assertExists()
    }

    @Test fun dataSourceDialogTestsAndSavesAConnection() {
        rule.setContent { App(state) }
        val db = tempDb()
        rule.onNodeWithContentDescription("Nuevo data source").performClick()
        untilTag("datasource-dialog")

        rule.onNodeWithTag("ds-name").performTextClearance()
        rule.onNodeWithTag("ds-name").performTextInput("mi base")
        rule.onNodeWithTag("ds-db").performTextInput(db)
        rule.onNodeWithTag("ds-test").performClick()
        until { rule.onAllNodesWithTag("ds-test-result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ds-test-result").assertTextContains("SQLite", substring = true)

        rule.onNodeWithTag("ds-ok").performClick()
        until { rule.onAllNodesWithTag("datasource-dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals("mi base", state.form.name)
        assertEquals(listOf("mi base"), state.saved.map { it.name })
        until { state.explorer != null }
    }

    @Test fun applySavesWithoutClosingTheDialog() {
        rule.setContent { App(state) }
        val db = tempDb()
        rule.onNodeWithContentDescription("Nuevo data source").performClick()
        untilTag("datasource-dialog")
        rule.onNodeWithTag("ds-name").performTextClearance()
        rule.onNodeWithTag("ds-name").performTextInput("aplicada")
        rule.onNodeWithTag("ds-db").performTextInput(db)
        rule.onNodeWithTag("ds-apply").performClick()
        until { state.saved.map { it.name } == listOf("aplicada") }
        rule.onNodeWithTag("datasource-dialog").assertExists()
        rule.onNodeWithTag("ds-row:aplicada").assertExists()
        rule.onNodeWithTag("ds-cancel").performClick()
        until { rule.onAllNodesWithTag("datasource-dialog").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun connectionTypeSwitchEnablesTheUrlField() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick()
        untilTag("datasource-dialog")
        fun editable() = rule.onNodeWithTag("ds-url").fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsEditable)
        assertEquals(false, editable())                       // derived from the fields, read-only
        rule.onNodeWithTag("ds-conn-type:1").performClick(); frames()
        assertEquals(true, editable())                        // "Solo URL": the user types it
        rule.onNodeWithTag("ds-conn-type:0").performClick(); frames()
        assertEquals(false, editable())
    }

    @Test fun dataSourceDialogReportsAFailedConnection() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick()
        untilTag("datasource-dialog")
        rule.onNodeWithTag("ds-db").performTextInput("/no/such/dir/x.db")
        rule.onNodeWithTag("ds-test").performClick()
        until { rule.onAllNodesWithTag("ds-test-result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ds-test-result").assertTextContains("✗", substring = true)
        rule.onNodeWithTag("ds-cancel").performClick()
        until { rule.onAllNodesWithTag("datasource-dialog").fetchSemanticsNodes().isEmpty() }
        assertTrue(state.saved.isEmpty())
    }

    @Test fun engineMenuListsEverySupportedDatabase() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick()
        untilTag("datasource-dialog")
        rule.onNodeWithTag("ds-kind").performClick(); frames()
        for (k in listOf("POSTGRES", "MYSQL", "MARIADB", "SQLITE", "MSSQL", "ORACLE")) rule.onNodeWithTag("kind:$k").assertExists()
    }

    @Test fun themeButtonCyclesAndPersists() {
        rule.setContent { App(state) }
        assertEquals(ThemeMode.SYSTEM, state.settings.themeMode)
        rule.onNodeWithContentDescription("Tema: sistema").performClick(); frames()
        assertEquals(ThemeMode.LIGHT, state.settings.themeMode)
        rule.onNodeWithContentDescription("Tema: claro").performClick(); frames()
        assertEquals(ThemeMode.DARK, state.settings.themeMode)
        assertEquals(ThemeMode.DARK, SettingsStore(settingsDir).load().themeMode)   // survives a restart
    }

    @Test fun resultTabsCanBeClosedAndTheOutputShowsTheLog() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("select 1 as a; select 2 as b")
        state.runScript()
        until { !state.running && state.tabs.size == 2 }
        rule.onNodeWithContentDescription("Cerrar Resultado 1").performClick(); frames()
        assertEquals(listOf("Resultado 2"), state.tabs.map { it.title })

        rule.onNodeWithTag("tab:output").performClick(); frames()
        rule.onNode(hasText("select 1 as a", substring = true) and hasAnyAncestor(hasTestTag("output"))).assertExists()
    }

    @Test fun editingClearsTheRunMarker() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("select 1")
        state.runSelectionOrCurrent()
        until { !state.running }
        assertTrue(state.lastRun?.ok == true)
        setEditor("select 2")
        assertEquals(null, state.lastRun)
    }

    @Test fun whereAndOrderByFiltersReRunTheTableTab() {
        rule.setContent { App(state) }
        state.form.database = tempDb()
        setEditor("create table customers(id integer primary key, name text); insert into customers values (1, 'Ada'), (2, 'Grace'), (3, 'Linus');")
        runAll()
        until { !state.running && state.catalog?.complete == true }
        state.openData(state.catalog!!.objectOf(ksqlc.sql.TableRef("main", "customers"))!!)
        until { state.dataTabs.singleOrNull()?.grid?.rows?.size == 3 }
        val tab = state.dataTabs[0]
        rule.onNodeWithTag("filter-where").assertExists()
        rule.onNodeWithTag("filter-order").assertExists()

        rule.onNodeWithTag("filter-where").performTextInput("id > 1")
        rule.onNodeWithTag("filter-order").performTextInput("name desc")
        rule.onNodeWithTag("filter-order").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        until { tab.grid.rows.map { it[1] } == listOf("Linus", "Grace") }
        assertEquals("select * from \"main\".\"customers\" where id > 1 order by name desc".lowercase().replace("\"main\".", "").replace("\"", ""), tab.query().lowercase().replace("\"main\".", "").replace("\"", ""))

        // a bad filter keeps the previous rows and reports the error
        rule.onNodeWithTag("filter-where").performTextInput(" and nope = 1")
        rule.onNodeWithTag("filter-where").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        until { state.status.startsWith("Error") }
        assertEquals(listOf("Linus", "Grace"), tab.grid.rows.map { it[1] })

        // the ✕ empties a box and re-runs the query without that clause
        rule.onNodeWithTag("filter-where-clear").performClick()
        until { tab.where.isEmpty() && tab.grid.rows.map { it[1] } == listOf("Linus", "Grace", "Ada") }
        rule.onNodeWithTag("filter-order-clear").performClick()
        until { tab.orderBy.isEmpty() && tab.grid.rows.size == 3 }
        assertEquals(0, rule.onAllNodesWithTag("filter-where-clear").fetchSemanticsNodes().size)
    }

    @Test fun filterTextIsColouredAndUnknownColumnsAreErrors() {
        val a = highlightFilter("nombre like '%x%' and nope = 1 and lower(nombre) = 'a'", ksqlc.app.ui.DarkColors, setOf("nombre"))
        fun colourAt(i: Int) = a.spanStyles.filter { i in it.start until it.end }.lastOrNull()?.item?.color
        val text = a.text
        assertEquals(ksqlc.app.ui.DarkColors.synIdent, colourAt(0))                       // known column
        assertEquals(ksqlc.app.ui.DarkColors.synKeyword, colourAt(text.indexOf("like")))
        assertEquals(ksqlc.app.ui.DarkColors.synString, colourAt(text.indexOf("'%x%'") + 1))
        assertEquals(ksqlc.app.ui.DarkColors.error, colourAt(text.indexOf("nope")))        // not a column
        assertEquals(ksqlc.app.ui.DarkColors.synIdent, colourAt(text.indexOf("lower")))    // a call, not a column
    }
}
