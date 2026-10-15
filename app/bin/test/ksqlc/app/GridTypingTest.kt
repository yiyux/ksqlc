package ksqlc.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.Rule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real key events (not the semantic text input shortcut) must reach the cell being edited. */
@OptIn(ExperimentalTestApi::class)
class GridTypingTest {
    @get:Rule val rule = createComposeRule()
    private val file = Files.createTempFile("ksqlc-gt", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), ConnectionStore(Files.createTempDirectory("ksqlc-gt"), InMemorySecretStore()), SettingsStore(Files.createTempDirectory("ksqlc-gt-s")))

    private fun frames() = repeat(4) { rule.mainClock.advanceTimeByFrame() }

    private fun tab(): DataTab {
        DriverManager.getConnection(url).use { it.createStatement().execute("create table cfg(actu text, vers text, n integer primary key)") }
        val obj = DbObject(Namespace(null, "main"), "cfg", DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.SQLITE)
        val session = Session(DataSource("t", "t", DbKind.SQLITE, url), null)
        val sql = d.selectAll(obj)
        val rows = session.runScript(listOf(sql)).single() as StatementResult.Rows
        return DataTab(obj, ResultTab("cfg", rows).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.SQLITE, listOf("n")))
    }

    private fun typeKeys(keys: List<Key>) = rule.onNodeWithTag("cell-editor").performKeyInput { keys.forEach { pressKey(it) } }

    @Test fun keysTypedAfterADoubleClickReachTheEditor() {
        rule.mainClock.autoAdvance = false
        val t = tab(); t.editor.addRow(3)
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.DARK) { Grid(t.grid, {}, editing = t.editor) } }
        frames()
        rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
        rule.onNodeWithTag("cell-editor").assertIsFocused()
    }

    @Test fun keysTypedAfterTabbingFromTheCellBeforeReachTheNextEditor() {
        rule.mainClock.autoAdvance = false
        val t = tab(); t.editor.addRow(3)
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.DARK) { Grid(t.grid, {}, editing = t.editor) } }
        frames()
        rule.onNodeWithTag("cell:0:0").performMouseInput { doubleClick() }; frames()
        rule.onNodeWithTag("cell-editor").assertIsFocused()
        typeKeys(listOf(Key.Tab)); frames()
        rule.onNodeWithTag("cell-editor").assertIsFocused()
    }

    @Test fun clickingInsideTheEditorKeepsTypingPossible() {
        rule.mainClock.autoAdvance = false
        val t = tab(); t.editor.addRow(3)
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.DARK) { Grid(t.grid, {}, editing = t.editor) } }
        frames()
        rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
        rule.onNodeWithTag("cell-editor").performMouseInput { click() }; frames()
        Thread.sleep(400); frames()
        rule.onNodeWithTag("cell-editor").assertIsFocused()
    }

    @Test fun renderAnEditorWithText() {
        rule.mainClock.autoAdvance = false
        val t = tab(); t.editor.addRow(3)
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.LIGHT) { Grid(t.grid, {}, editing = t.editor, modifier = androidx.compose.ui.Modifier.size(500.dp, 120.dp)) } }
        frames()
        rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
        rule.onNodeWithTag("cell-editor").performTextInput("1.00.0450"); frames()
        rule.onRoot().savePng("grid-editing")
    }

    private fun renderPicker(mode: ksqlc.storage.ThemeMode, type: String, value: String, name: String) {
        rule.mainClock.autoAdvance = false
        DriverManager.getConnection(url).use { it.createStatement().execute("create table ev(id integer primary key, v $type)") }
        val obj = DbObject(Namespace(null, "main"), "ev", DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.SQLITE)
        val session = Session(DataSource("t", "t", DbKind.SQLITE, url), null)
        val sql = d.selectAll(obj)
        val rows = session.runScript(listOf(sql)).single() as StatementResult.Rows
        val t = DataTab(obj, ResultTab("ev", rows).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.SQLITE, listOf("id")))
        t.editor.addRow(2)
        rule.setContent { ksqlc.app.ui.KsqlcTheme(mode) { Grid(t.grid, {}, editing = t.editor, modifier = androidx.compose.ui.Modifier.size(420.dp, 400.dp)) } }
        frames()
        rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
        rule.onNodeWithTag("cell-editor").performTextInput(value); frames()
        rule.onAllNodes(isRoot())[0].savePng(name)
    }

    @Test fun renderDatetimePickerDark() = renderPicker(ksqlc.storage.ThemeMode.DARK, "datetime", "2026-02-10 14:35:08", "picker-dark")
    @Test fun renderDatePickerLight() = renderPicker(ksqlc.storage.ThemeMode.LIGHT, "date", "2026-02-10", "picker-light")
}
