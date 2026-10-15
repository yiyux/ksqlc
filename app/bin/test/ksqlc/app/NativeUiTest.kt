package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.findTool
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NativeUiTest {
    @get:Rule val rule = createComposeRule()

    private val settings = SettingsStore(Files.createTempDirectory("ksqlc-ui-ns"))
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), ConnectionStore(Files.createTempDirectory("ksqlc-ui-n"), InMemorySecretStore()), settings)
    private val people = DbObject(Namespace(null, "main"), "people", DbObjectKind.TABLE)

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
    private fun connect(sql: String) {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor(sql)
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }
    }

    @Test fun dumpsATableWithSqlite3AndRemembersTheTool() {
        assumeTrue("sqlite3 no está instalado", findTool(listOf("sqlite3")) != null)
        connect("create table people(id integer primary key, name text); insert into people values (1, 'Ada');")
        state.openNative(false, people)
        until { rule.onAllNodesWithTag("native-dialog").fetchSemanticsNodes().isNotEmpty() }
        val file = Files.createTempFile("ksqlc-ui-dump", ".sql")
        rule.onNodeWithTag("native-file").performTextInput(file.toString())
        rule.onNodeWithTag("native-run").performClick()
        until { rule.onAllNodesWithTag("native-dialog").fetchSemanticsNodes().isEmpty() || state.nativeJob?.error != null }
        assertEquals(null, state.nativeJob?.error, state.log.toString())
        val text = Files.readString(file)
        assertTrue("CREATE TABLE people" in text && "INSERT INTO people VALUES(1,'Ada')" in text, text)
        assertTrue(state.log.any { "terminado" in it }, state.log.toString())
        assertNotNull(settings.load().toolPaths["sqlite3"])
    }

    @Test fun restoreAsksFirstThenRunsAndRefreshesTheExplorer() {
        assumeTrue("sqlite3 no está instalado", findTool(listOf("sqlite3")) != null)
        connect("select 1")
        val file = Files.createTempFile("ksqlc-ui-restore", ".sql").also { Files.writeString(it, "create table restored(a integer);") }
        state.openNative(true)
        until { rule.onAllNodesWithTag("native-dialog").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("native-file").performTextInput(file.toString())
        rule.onNodeWithTag("native-run").performClick()
        until { rule.onAllNodesWithTag("confirm-dialog").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("confirm-ok").performClick()
        until { state.nativeJob == null }
        state.run(listOf("select name from sqlite_master where name = 'restored'")); until { !state.running }
        assertEquals("restored", state.tabs.single().rows.single().single())
    }

    @Test fun manualUrlsAndUnsupportedEnginesAreExplained() {
        rule.setContent { App(state) }
        state.form.customUrl = true; state.form.urlText = "jdbc:postgresql://h/db?x=1"
        state.openNative(false)
        assertTrue(state.nativeJob == null && "URL manual" in state.status, state.status)
        state.form.urlText = "jdbc:sqlserver://h:1433;databaseName=x;encrypt=false"
        state.openNative(false)
        assertTrue(state.nativeJob == null)
    }
}
