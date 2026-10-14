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
import kotlin.test.assertFalse
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

    @Test fun savedConnectionsAreListedOnStartupAndClickingOneConnects() {
        val store = ConnectionStore(Files.createTempDirectory("ksqlc-ui-saved"), InMemorySecretStore())
        val db = tempDb()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { it.createStatement().execute("create table piezas(id integer primary key)") }
        store.save(ksqlc.core.DataSource("id-1", "mi base", ksqlc.core.DbKind.SQLITE, "jdbc:sqlite:$db", null), "")
        store.save(ksqlc.core.DataSource("id-2", "otra", ksqlc.core.DbKind.SQLITE, "jdbc:sqlite:$db", null), "")
        val st = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-saved-settings")))
        try {
            rule.setContent { App(st) }
            frames()
            // before connecting, the explorer already lists every configured data source
            rule.onNodeWithTag("ds-node:mi base").assertExists()
            rule.onNodeWithTag("ds-node:otra").assertExists()
            assertEquals(null, st.explorer)

            rule.onNodeWithTag("ds-node:mi base").performClick()
            val end = System.nanoTime() + 20_000_000_000L
            while (st.explorer == null || rule.onAllNodesWithTag("tree:ns:.main").fetchSemanticsNodes().isEmpty()) {
                check(System.nanoTime() < end) { "timeout conectando (${st.status})" }
                Thread.sleep(50); rule.mainClock.advanceTimeByFrame()
            }
            assertEquals("mi base", st.form.name)
            rule.onNodeWithTag("ds-node:otra").assertExists()          // the others stay listed

            rule.onNodeWithTag("ds-node:mi base").performClick(); frames()   // collapses the connected one
            assertEquals(0, rule.onAllNodesWithTag("tree:ns:.main").fetchSemanticsNodes().size)
        } finally { st.dispose() }
    }

    /** Opens `customers` (Ada, Grace, Linus) in a table tab of [st]; returns the tab. */
    private fun openCustomers(st: AppState, manual: Boolean = false): DataTab {
        st.form.database = tempDb()
        st.onEditorChange(androidx.compose.ui.text.input.TextFieldValue("create table customers(id integer primary key, name text, age integer); insert into customers values (1, 'Ada', 36), (2, 'Grace', 85), (3, 'Linus', 54);"))
        st.runScript()
        until { !st.running && st.catalog?.complete == true }
        st.openData(st.catalog!!.objectOf(ksqlc.sql.TableRef("main", "customers"))!!)
        until { st.dataTabs.singleOrNull()?.grid?.rows?.size == 3 }
        val tab = st.dataTabs[0]
        if (manual) { st.setTxManual(true); until { !tab.session.autoCommit } }   // the data is created in auto mode, then the connection goes manual
        return tab
    }

    private fun editCell(i: Int, j: Int, text: String) {
        rule.onNodeWithTag("cell:$i:$j").performMouseInput { doubleClick() }
        untilTag("cell-editor")
        rule.onNodeWithTag("cell-editor").performTextClearance()
        rule.onNodeWithTag("cell-editor").performTextInput(text)
        rule.onNodeWithTag("cell-editor").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        frames()
    }

    private fun dbNames(db: String) = java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().executeQuery("select name from customers order by id").let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test fun editingACellMarksItPreviewsTheSqlAndSendsIt() {
        rule.setContent { App(state) }
        val tab = openCustomers(state)
        editCell(1, 1, "Grace H.")
        assertEquals(1, tab.editor.edits.size)
        rule.onNodeWithTag("pending-count").assertTextContains("1 cambio", substring = true)
        rule.onNodeWithTag("cell:1:1").assertTextContains("Grace H.")

        rule.onNodeWithContentDescription("Ver SQL de los cambios").performClick()
        untilTag("sql-dialog")
        rule.onNode(hasText("update", substring = true, ignoreCase = true) and hasAnyAncestor(hasTestTag("sql-dialog"))).assertExists()
        rule.onNode(hasText("'Grace H.'", substring = true) and hasAnyAncestor(hasTestTag("sql-dialog"))).assertExists()
        rule.onNodeWithContentDescription("Cerrar").performClick(); frames()

        rule.onNodeWithContentDescription("Enviar cambios").performClick()
        until { !tab.editor.hasChanges && tab.grid.rows[1][1] == "Grace H." }
        assertEquals(listOf("Ada", "Grace H.", "Linus"), dbNames(state.form.database))
    }

    @Test fun revertDropsEditsAndEscapeCancelsAnEdit() {
        rule.setContent { App(state) }
        val tab = openCustomers(state)
        editCell(0, 1, "Changed")
        assertTrue(tab.editor.hasChanges)
        rule.onNodeWithContentDescription("Revertir cambios").performClick(); frames()
        assertFalse(tab.editor.hasChanges)

        rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }
        untilTag("cell-editor")
        rule.onNodeWithTag("cell-editor").performTextInput("zzz")
        rule.onNodeWithTag("cell-editor").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }; frames()
        assertFalse(tab.editor.hasChanges)
        assertEquals(0, rule.onAllNodesWithTag("cell-editor").fetchSemanticsNodes().size)
    }

    @Test fun addingAndDeletingRowsFromTheToolbar() {
        rule.setContent { App(state) }
        val tab = openCustomers(state)
        rule.onNodeWithContentDescription("Añadir fila").performClick(); frames()
        assertEquals(1, tab.editor.inserts.size)
        editCell(3, 1, "Alan")
        editCell(3, 0, "4")
        rule.onNodeWithTag("rownum:0").performMouseInput { click() }          // select Ada's row
        rule.onNodeWithContentDescription("Borrar filas seleccionadas").performClick(); frames()
        assertTrue(0 in tab.editor.deleted)
        rule.onNodeWithContentDescription("Enviar cambios").performClick()
        until { !tab.editor.hasChanges && tab.grid.rows.size == 3 }
        assertEquals(listOf("Grace", "Linus", "Alan"), dbNames(state.form.database))
    }

    @Test fun manualTransactionsHoldTheSendUntilCommitAndRollbackDiscardsIt() {
        rule.setContent { App(state) }
        val tab = openCustomers(state, manual = true)
        rule.onNodeWithTag("tx-mode:tab").assertTextContains("Manual", substring = true)
        rule.onNodeWithTag("commit:tab").assertExists()

        editCell(0, 1, "Pending")
        rule.onNodeWithContentDescription("Enviar cambios").performClick()
        until { tab.pending }
        rule.onNodeWithTag("tx-pending:tab", useUnmergedTree = true).assertExists()
        assertEquals(listOf("Ada", "Grace", "Linus"), dbNames(state.form.database))     // not confirmed yet
        rule.onNodeWithTag("commit:tab").performClick()
        until { !tab.pending }
        assertEquals(listOf("Pending", "Grace", "Linus"), dbNames(state.form.database))

        editCell(1, 1, "Oops")
        rule.onNodeWithContentDescription("Enviar cambios").performClick()
        until { tab.pending }
        rule.onNodeWithTag("rollback:tab").performClick()
        until { !tab.pending }
        assertEquals(listOf("Pending", "Grace", "Linus"), dbNames(state.form.database))
    }

    @Test fun closingATabWithPendingEditsAsksFirst() {
        rule.setContent { App(state) }
        val tab = openCustomers(state)
        editCell(0, 1, "Unsent")
        rule.onNodeWithContentDescription("Cerrar customers").performClick()
        untilTag("confirm-dialog")
        assertEquals(1, state.dataTabs.size)
        rule.onNodeWithTag("confirm-cancel").performClick(); frames()
        assertEquals(1, state.dataTabs.size); assertTrue(tab.editor.hasChanges)

        rule.onNodeWithContentDescription("Cerrar customers").performClick()
        untilTag("confirm-ok")
        rule.onNodeWithTag("confirm-ok").performClick(); frames()
        assertTrue(state.dataTabs.isEmpty())
    }

    @Test fun switchingToAutoIsRefusedWhileWorkIsUnconfirmed() {
        rule.setContent { App(state) }
        val tab = openCustomers(state, manual = true)
        editCell(0, 1, "Held")
        rule.onNodeWithContentDescription("Enviar cambios").performClick()
        until { tab.pending }
        state.setTxManual(false)
        assertTrue(state.form.manualTx)
        assertTrue(state.status.contains("Commit"))
        state.commitTab(tab); until { !tab.pending }
        state.setTxManual(true)   // no-op (already manual)
        state.setTxManual(false)
        assertFalse(state.form.manualTx)
    }
}
