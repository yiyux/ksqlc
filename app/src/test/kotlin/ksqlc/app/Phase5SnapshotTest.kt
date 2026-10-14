package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.Session
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.Settings
import ksqlc.storage.SettingsStore
import ksqlc.storage.ThemeMode
import java.nio.file.Files
import kotlin.test.Test

/** The history, export, import, dump and diff dialogs over the window, in both themes, for visual review (`app/build/snapshots`). */
@OptIn(ExperimentalTestApi::class)
class Phase5SnapshotTest {
    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-snap5", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { it.runScript(sql.toList()) }
        return f
    }
    private val schemaA = arrayOf("create table cliente(id integer primary key, nombre text not null, email text)", "create table pedido(id integer primary key, cliente_id integer references cliente(id), total real)",
        "create table solo_izquierda(x integer)", "create view v_cliente as select id, nombre from cliente", "insert into cliente values (1, 'Ada', 'ada@example.com'), (2, 'Grace', null)")
    private val schemaB = arrayOf("create table cliente(id integer primary key, nombre text not null, email text, telefono text)", "create table pedido(id integer primary key, cliente_id integer references cliente(id), total real)",
        "create table solo_derecha(y integer)", "create view v_cliente as select id, nombre from cliente")

    private fun scene(mode: ThemeMode, name: String, show: suspend (AppState, ComposeUiTest) -> Unit) = runDesktopComposeUiTest(1722, 1000) {
        mainClock.autoAdvance = false
        val store = ConnectionStore(Files.createTempDirectory("ksqlc-snap5-store"), InMemorySecretStore())
        store.save(DataSource("a", "demo@localhost", DbKind.SQLITE, "jdbc:sqlite:${db(*schemaA)}", null), "")
        store.save(DataSource("b", "staging", DbKind.SQLITE, "jdbc:sqlite:${db(*schemaB)}", null), "")
        val history = HistoryStore(Files.createTempDirectory("ksqlc-snap5-hist"))
        val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-snap5-set")).also { it.save(Settings(themeMode = mode)) }, history)
        try {
            setContent { App(state) }
            fun until(ms: Long = 20_000, cond: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!cond()) { check(System.nanoTime() < end) { "timeout (${state.status})" }; Thread.sleep(40); mainClock.advanceTimeByFrame() } }
            until { state.form.currentId == "a" }
            state.onEditorChange(TextFieldValue("select * from cliente;\nselect * from no_existe;"))
            state.runScript()
            until { !state.running && state.explorer != null && state.catalog?.complete == true }
            until { history.load("a").size == 2 }
            kotlinx.coroutines.runBlocking { show(state, this@runDesktopComposeUiTest) }
            repeat(8) { mainClock.advanceTimeByFrame() }
            onRoot().savePng(name)
        } finally { state.dispose() }
    }

    private fun both(name: String, show: suspend (AppState, ComposeUiTest) -> Unit) {
        scene(ThemeMode.DARK, "$name-dark", show); scene(ThemeMode.LIGHT, "$name-light", show)
    }

    private fun waitFor(t: ComposeUiTest, ms: Long = 20_000, cond: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!cond()) { check(System.nanoTime() < end) { "timeout" }; Thread.sleep(40); t.mainClock.advanceTimeByFrame() } }

    @Test fun history() = both("history") { s, t -> s.openHistory(); waitFor(t) { s.historyEntries != null } }

    @Test fun export() = both("export") { s, t ->
        s.exportResult(s.tabs.first()); waitFor(t) { s.exportJob != null }
        waitFor(t) { s.exportJob!!.sample != null }
        s.exportJob!!.editPath("~/Descargas/cliente.csv")
        s.exportJob!!.header = true
    }

    @Test fun import() = both("import") { s, t ->
        val csv = Files.createTempFile("clientes", ".csv").also { Files.writeString(it, "id,nombre,email\n3,Linus,linus@example.com\n4,\"Hopper, G.\",\n") }
        s.openImport(DbObject(Namespace(null, "main"), "cliente", DbObjectKind.TABLE)); waitFor(t) { s.importJob != null }
        s.importJob!!.path = csv.toString()
        waitFor(t) { s.importJob!!.preview.isNotEmpty() }
    }

    @Test fun dump() = both("dump") { s, t ->
        s.openNative(false, DbObject(Namespace(null, "main"), "cliente", DbObjectKind.TABLE)); waitFor(t) { s.nativeJob != null }
        s.nativeJob!!.file = "~/Descargas/cliente.sql"
    }

    @Test fun diff() = both("diff") { s, t ->
        s.openDiff(Namespace(null, "main"), "cliente"); waitFor(t) { s.diffJob?.rightNamespaces?.isNotEmpty() == true }
        s.diffJob!!.chooseSource(1); waitFor(t) { s.diffJob!!.rightIndexSource == 1 && s.diffJob!!.rightNamespaces.isNotEmpty() }
        s.diffJob!!.compare(); waitFor(t) { s.diffJob!!.results != null }
    }
}
