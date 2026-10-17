package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.RowStatus
import ksqlc.drivers.Session
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ContentDiffUiTest {
    @get:Rule val rule = createComposeRule()

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-ui-cdiff", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { it.runScript(sql.toList()) }
        return f
    }
    private val bFile = db("create table t(id integer primary key, v text)", "insert into t values (1,'a'),(2,'x'),(4,'d')")
    private val store = ConnectionStore(Files.createTempDirectory("ksqlc-ui-cd"), InMemorySecretStore()).also {
        it.save(DataSource("a", "a-izquierda", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text)", "insert into t values (1,'a'),(2,'b'),(3,'c')")), null)
        it.save(DataSource("b", "b-derecha", DbKind.SQLITE, "jdbc:sqlite:$bFile"), null)
        it.save(DataSource("c", "c-solo-lectura", DbKind.SQLITE, "jdbc:sqlite:$bFile", options = ConnectionOptions(readOnly = true)), null)
    }
    private fun rightRows(): List<String> = java.sql.DriverManager.getConnection("jdbc:sqlite:$bFile").use { c ->
        c.createStatement().executeQuery("select id || ':' || v from t order by id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
    private fun compared(source: String): ContentDiffJob {
        rule.setContent { App(state) }
        until { state.form.currentId == "a" }
        state.connect(); until { state.explorer != null }
        state.openContentDiff(DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE))
        until { exists("content-diff-dialog") }
        val job = state.contentDiffJob!!
        job.chooseSource(job.sources.indexOfFirst { it.id == source })
        until { job.rightTables.isNotEmpty() }
        rule.onNodeWithTag("content-diff-run").performClick()
        until { job.result != null || job.error != null }
        return job
    }
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-cds")))

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}' error=${state.contentDiffJob?.error})" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun comparesTheRowsOfATableWithAnotherSavedConnection() {
        rule.setContent { App(state) }
        until { state.form.currentId == "a" }
        state.connect(); until { state.explorer != null }
        state.openContentDiff(DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE))
        until { exists("content-diff-dialog") }
        val job = state.contentDiffJob!!
        job.chooseSource(1)
        until { job.rightTables.isNotEmpty() }
        rule.onNodeWithTag("content-diff-run").performClick()
        until { job.result != null || job.error != null }
        assertEquals(null, job.error)
        assertEquals(1, job.result!!.same)
        assertEquals(setOf(RowStatus.DIFFERENT, RowStatus.ONLY_LEFT, RowStatus.ONLY_RIGHT), job.result!!.rows.map { it.status }.toSet())
        until { exists("content-diff-row:2") }
    }

    @Test fun syncingMakesTheRightTableEqualAndDeletingIsOptIn() {
        val job = compared("b")
        assertEquals(listOf("1:a", "2:x", "4:d"), rightRows())
        assertFalse(job.syncDelete)                                        // deleting has to be asked for
        rule.onNodeWithTag("content-diff-sync-delete").performClick()
        until { job.syncDelete }
        rule.onNodeWithTag("content-diff-sync-sql").performClick()
        until { state.ddlView != null }
        assertTrue(state.ddlView!!.text.contains("delete") && state.ddlView!!.text.contains("update") && state.ddlView!!.text.contains("insert"), state.ddlView!!.text)
        state.ddlView = null
        rule.onNodeWithTag("content-diff-sync-apply").performClick()
        until { state.confirmation != null }
        assertTrue(state.confirmation!!.message.contains("se insertarán 1 filas, se actualizarán 1 y se borrarán 1"), state.confirmation!!.message)
        assertEquals(listOf("1:a", "2:x", "4:d"), rightRows())             // nothing yet: the confirmation comes first
        rule.onNodeWithTag("confirm-ok").performClick()
        until { job.syncMessage != null && job.result != null && !job.running }
        assertEquals(listOf("1:a", "2:b", "3:c"), rightRows())
        assertEquals(emptyList(), job.result!!.rows)                       // compared again afterwards: nothing left
    }

    @Test fun withoutDeletingTheRowOnlyOnTheRightStays() {
        val job = compared("b")
        rule.onNodeWithTag("content-diff-sync-apply").performClick()
        until { state.confirmation != null }
        rule.onNodeWithTag("confirm-ok").performClick()
        until { job.syncMessage != null }
        assertEquals(listOf("1:a", "2:b", "3:c", "4:d"), rightRows())
    }

    @Test fun aReadOnlyConnectionCannotBeSyncedAndSaysWhy() {
        val job = compared("c")
        until { exists("content-diff-sync-reason") }
        assertTrue(job.syncReason!!.contains("solo lectura"), job.syncReason)
        rule.onNodeWithTag("content-diff-sync-apply").assertIsNotEnabled()
        assertEquals(listOf("1:a", "2:x", "4:d"), rightRows())
    }
}
