package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import ksqlc.core.DataSource
import ksqlc.core.DbKind
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.Settings
import ksqlc.storage.SettingsStore
import ksqlc.storage.ThemeMode
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test

private val QUERY = """-- Artículos con poco stock
select id, codigo, nombre, stock, precio
from articulo
where stock < 5 and nombre like '%gel%'
order by stock, nombre;"""

/** Renders the whole window at the reference size (1722x1000) with real data, in both themes, for visual review. */
@OptIn(ExperimentalTestApi::class)
class WindowSnapshotTest {
    private fun seededDb(): String {
        val db = Files.createTempFile("ksqlc-snap", ".db").also { it.toFile().deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("create table articulo(id integer primary key, codigo text, nombre text not null, stock integer, precio real, iva integer, obs text)")
                st.executeUpdate("create table cliente(id integer primary key, nombre text, email text)")
                st.executeUpdate("create table pedido(id integer primary key, cliente_id integer references cliente(id), fecha text, total real)")
                st.executeUpdate("create index pedido_cliente on pedido(cliente_id)")
                st.executeUpdate("create view v_stock_bajo as select * from articulo where stock < 5")
                val names = listOf("Flúor Gel", "Cubeta para flúor", "Botellas de alcohol", "Sobres Craft", "Jabón Spray", "Diluyente", "Guantes M", "Gel hidroalcohólico", "Bandejas", "Alginatos", "Bolsas de yeso", "Gel limpiador")
                for (i in 1..30) st.executeUpdate("insert into articulo values ($i, '${"%06d".format(i)}', '${names[i % names.size]}', ${i % 7}, ${i * 1.25}, ${if (i % 5 == 0) "null" else "21"}, ${if (i % 4 == 0) "null" else "'bodega'"})")
            }
        }
        return db.toString()
    }

    private fun window(mode: ThemeMode, name: String, openTable: Boolean = false) = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val state = AppState(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ConnectionStore(Files.createTempDirectory("ksqlc-snap-store"), InMemorySecretStore()),
            SettingsStore(Files.createTempDirectory("ksqlc-snap-settings")).also { it.save(Settings(themeMode = mode)) },
        )
        try {
            state.form.name = "demo@localhost"; state.form.database = seededDb()
            setContent { App(state) }
            state.onEditorChange(TextFieldValue(QUERY, TextRange(QUERY.indexOf("from") + 2)))
            state.runSelectionOrCurrent()
            fun until(ms: Long = 20_000, cond: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!cond()) { check(System.nanoTime() < end) { "timeout (${state.status})" }; Thread.sleep(40); mainClock.advanceTimeByFrame() } }
            until { !state.running && state.explorer != null }
            val model = state.explorer!!
            fun find(n: TreeNode, id: String): TreeNode? = if (n.id == id) n else n.children?.firstNotNullOfOrNull { find(it, id) }
            fun node(id: String): TreeNode = model.roots.firstNotNullOf { find(it, id) }
            runBlocking {
                model.expand(node("ns:.main"))
                model.expand(node("ns:.main/Tablas"))
                model.expand(node("ns:.main/Tablas/articulo"))
                model.expand(node("ns:.main/Tablas/articulo/cols"))
                model.expand(node("ns:.main/Vistas"))
            }
            if (openTable) {
                val o = model.source.let { (node("ns:.main/Tablas/articulo").obj ?: error("sin objeto")) }
                state.openData(o)
                until { state.dataTabs.isNotEmpty() }
                state.dataTabs[0].where = "nombre like '%gel%' and stock < 5"; state.dataTabs[0].orderBy = "stock desc, nombre"
                state.applyFilters(state.dataTabs[0])
                until { state.dataTabs[0].grid.rows.size in 1..29 }
                state.dataTabs[0].where += " and nope = 1"   // typed but not applied: shows the unknown-column colour
            }
            repeat(4) { mainClock.advanceTimeByFrame() }
            onRoot().savePng(name)
        } finally { state.dispose() }
    }

    @Test fun darkWindow() = window(ThemeMode.DARK, "window-dark")
    @Test fun darkDataTab() = window(ThemeMode.DARK, "tab-dark", openTable = true)
    @Test fun lightWindow() = window(ThemeMode.LIGHT, "window-light")

    private fun dialog(mode: ThemeMode, name: String) = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val store = ConnectionStore(Files.createTempDirectory("ksqlc-snap-store"), InMemorySecretStore())
        val saved = listOf(
            DataSource("1", "demo@localhost", DbKind.SQLITE, "jdbc:sqlite::memory:"),
            DataSource("2", "ventas@db.local", DbKind.POSTGRES, "jdbc:postgresql://db.local:5432/ventas?sslmode=require".replace("?sslmode=require", "") , "ana"),
            DataSource("3", "postgres@localhost", DbKind.POSTGRES, "jdbc:postgresql://localhost:5432/postgres"),
            DataSource("4", "tienda@mysql", DbKind.MYSQL, "jdbc:mysql://10.0.0.5:3306/tienda", "root"),
        )
        saved.forEach { store.save(it, null) }
        val state = AppState(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), store,
            SettingsStore(Files.createTempDirectory("ksqlc-snap-settings")).also { it.save(Settings(themeMode = mode)) },
        )
        try {
            setContent { App(state) }
            state.select(saved[2])
            state.dataSourceDialog = true
            val end = System.nanoTime() + 3_000_000_000
            while (System.nanoTime() < end) { Thread.sleep(40); mainClock.advanceTimeByFrame() }
            onRoot().savePng(name)
        } finally { state.dispose() }
    }

    @Test fun darkDialog() = dialog(ThemeMode.DARK, "dialog-dark")
    @Test fun lightDialog() = dialog(ThemeMode.LIGHT, "dialog-light")

    /** The editor assistance on screen: a completion list, an unknown table, an unknown column, and the other uses of a name. */
    private fun assistance(mode: ThemeMode, name: String) = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val state = AppState(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ConnectionStore(Files.createTempDirectory("ksqlc-snap-store"), InMemorySecretStore()),
            SettingsStore(Files.createTempDirectory("ksqlc-snap-settings")).also { it.save(Settings(themeMode = mode)) },
        )
        try {
            state.form.name = "demo@localhost"; state.form.database = seededDb()
            setContent { App(state) }
            fun until(ms: Long = 20_000, cond: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!cond()) { check(System.nanoTime() < end) { "timeout (${state.status}) diag=${state.diagnostics} catalog=${state.catalog?.complete}" }; Thread.sleep(40); mainClock.advanceTimeByFrame() } }
            state.onEditorChange(TextFieldValue("select 1"))
            state.runScript()
            until { !state.running && state.catalog?.complete == true }
            val text = "select a.nombre, a.precio, a.stok\nfrom articulo a\nwhere a.nombre like 'g%' and a.precio > 1;\n\nselect * from nada;\n\nselect * from artic"
            state.onEditorChange(TextFieldValue(text, TextRange(text.indexOf("a.nombre") + 4)))
            until { state.diagnostics.any { "stok" in it.message } && state.diagnostics.any { "nada" in it.message } }
            val tail = TextFieldValue(text, TextRange(text.length))
            onNode(hasTestTag("editor")).performClick()
            state.onEditorChange(tail)
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("editor"))).performTextInput("u")
            until { onAllNodesWithTag("completion").fetchSemanticsNodes().isNotEmpty() && state.diagnostics.any { "articu" in it.message } }
            repeat(4) { mainClock.advanceTimeByFrame() }
            // the completion list is a popup with a root of its own: save both layers
            val roots = onAllNodes(isRoot())
            roots[0].savePng(name)
            if (roots.fetchSemanticsNodes().size > 1) roots[1].savePng("$name-popup")
        } finally { state.dispose() }
    }

    @Test fun darkAssistance() = assistance(ThemeMode.DARK, "assist-dark")
    @Test fun lightAssistance() = assistance(ThemeMode.LIGHT, "assist-light")

    /** First start with data sources already configured: the explorer lists them before anything is connected. */
    @Test fun explorerListsSavedDataSources() = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val store = ConnectionStore(Files.createTempDirectory("ksqlc-snap-store"), InMemorySecretStore())
        store.save(DataSource("a", "produccion", DbKind.POSTGRES, "jdbc:postgresql://db.example/app", "app"), "")
        store.save(DataSource("b", "demo@localhost", DbKind.SQLITE, "jdbc:sqlite:${seededDb()}", null), "")
        store.save(DataSource("c", "reportes", DbKind.MYSQL, "jdbc:mysql://db.example/rep", "rep"), "")
        val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-snap-settings")).also { it.save(Settings(themeMode = ThemeMode.DARK)) })
        try {
            setContent { App(state) }
            repeat(4) { mainClock.advanceTimeByFrame() }
            onRoot().savePng("explorer-saved-dark")
        } finally { state.dispose() }
    }

    /** A table tab with edited, new and deleted rows pending, and the dialog that previews their SQL. */
    private fun pending(mode: ThemeMode, name: String) = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val state = AppState(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ConnectionStore(Files.createTempDirectory("ksqlc-snap-store"), InMemorySecretStore()),
            SettingsStore(Files.createTempDirectory("ksqlc-snap-settings")).also { it.save(Settings(themeMode = mode)) },
        )
        try {
            state.form.name = "demo@localhost"; state.form.database = seededDb()
            setContent { App(state) }
            fun until(ms: Long = 20_000, cond: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!cond()) { check(System.nanoTime() < end) { "timeout (${state.status})" }; Thread.sleep(40); mainClock.advanceTimeByFrame() } }
            state.onEditorChange(TextFieldValue("select 1"))
            state.runScript()
            until { !state.running && state.catalog?.complete == true }
            state.openData(state.catalog!!.objectOf(ksqlc.sql.TableRef("main", "articulo"))!!)
            until { state.dataTabs.isNotEmpty() }
            val tab = state.dataTabs[0]
            tab.editor.setCell(27, 3, ksqlc.drivers.CellValue.Text("99"), tab.grid)
            tab.editor.setCell(27, 2, ksqlc.drivers.CellValue.Text("Cubeta renombrada"), tab.grid)
            tab.editor.setCell(28, 6, ksqlc.drivers.CellValue.Null, tab.grid)
            tab.editor.selectedRows.add(25); tab.editor.deleteSelected(tab.grid)
            tab.editor.addRow(tab.grid.result.columns.size)
            val n = tab.grid.rows.size
            tab.editor.setCell(n, 2, ksqlc.drivers.CellValue.Text("Artículo nuevo"), tab.grid)
            tab.editor.setCell(n, 3, ksqlc.drivers.CellValue.Text("10"), tab.grid)
            repeat(6) { mainClock.advanceTimeByFrame() }
            onRoot().savePng(name)
            state.previewChanges(tab)
            repeat(6) { mainClock.advanceTimeByFrame() }
            onRoot().savePng("$name-sql")
        } finally { state.dispose() }
    }

    @Test fun darkPending() = pending(ThemeMode.DARK, "pending-dark")
    @Test fun lightPending() = pending(ThemeMode.LIGHT, "pending-light")
}
