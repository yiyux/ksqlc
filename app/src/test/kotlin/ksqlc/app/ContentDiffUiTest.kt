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

@OptIn(ExperimentalTestApi::class)
class ContentDiffUiTest {
    @get:Rule val rule = createComposeRule()

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-ui-cdiff", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { it.runScript(sql.toList()) }
        return f
    }
    private val store = ConnectionStore(Files.createTempDirectory("ksqlc-ui-cd"), InMemorySecretStore()).also {
        it.save(DataSource("a", "a-izquierda", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text)", "insert into t values (1,'a'),(2,'b'),(3,'c')")), null)
        it.save(DataSource("b", "b-derecha", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text)", "insert into t values (1,'a'),(2,'x'),(4,'d')")), null)
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
}
