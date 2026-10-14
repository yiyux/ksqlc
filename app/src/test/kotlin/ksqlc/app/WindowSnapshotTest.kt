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
}
