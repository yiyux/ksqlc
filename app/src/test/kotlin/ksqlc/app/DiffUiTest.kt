package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.ObjectStatus
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DiffUiTest {
    @get:Rule val rule = createComposeRule()

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-ui-diff", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { it.runScript(sql.toList()) }
        return f
    }
    private val store = ConnectionStore(Files.createTempDirectory("ksqlc-ui-d"), InMemorySecretStore()).also {
        it.save(DataSource("a", "a-izquierda", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text)", "create table solo_a(x integer)", "create table igual(x integer)")), null)
        it.save(DataSource("b", "b-derecha", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text, extra integer)", "create table solo_b(x integer)", "create table igual(x integer)")), null)
    }
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-ds")))

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}' diff=${state.diffJob?.let { "running=${it.running} progress=${it.progress} error=${it.error} left=${it.leftNamespaces} right=${it.rightNamespaces}" }})" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun comparesTheActiveConnectionWithAnotherSavedOne() {
        rule.setContent { App(state) }
        until { state.form.currentId == "a" }
        state.connect(); until { state.explorer != null }
        state.openDiff(Namespace(null, "main"), "t")
        until { exists("diff-dialog") && state.diffJob!!.rightNamespaces.isNotEmpty() }

        rule.onNodeWithTag("diff-right-source").performClick()
        until { exists("diff-right-source:1") }
        rule.onNodeWithTag("diff-right-source:1").performClick()
        until { state.diffJob!!.rightIndexSource == 1 && state.diffJob!!.rightNamespaces.isNotEmpty() }
        until { !exists("diff-right-source:1") }   // a closing menu keeps blocking clicks until its exit animation ends
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("diff-run").performClick()
        until { state.diffJob!!.results != null || state.diffJob!!.error != null }
        assertEquals(null, state.diffJob!!.error)

        val job = state.diffJob!!
        assertEquals(
            listOf("igual" to ObjectStatus.SAME, "solo_a" to ObjectStatus.ONLY_LEFT, "solo_b" to ObjectStatus.ONLY_RIGHT, "t" to ObjectStatus.DIFFERENT),
            job.results!!.map { it.name to it.status }.sortedBy { it.first },
        )
        // opened from table t: it is the selected one, and the lines show the added column
        assertEquals("t", job.selected?.name)
        until { exists("diff-line:0") }
        assertTrue(job.selected!!.right!!.contains("extra"))
        // identical objects are hidden by default; the box brings them back
        until { !exists("diff-item:3") }
        rule.onNodeWithTag("diff-hide-same").performClick()
        until { exists("diff-item:3") }
    }

    @Test fun anUnreachableConnectionIsReportedNotThrown() {
        store.save(DataSource("c", "c-rota", DbKind.SQLITE, "jdbc:sqlite:/no/existe/x.db"), null)
        val st = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-ds2")))
        try {
            rule.setContent { App(st) }
            until { st.form.currentId == "a" }
            st.connect(); until { st.explorer != null }
            st.openDiff(null)
            until { st.diffJob!!.rightNamespaces.isNotEmpty() }
            st.diffJob!!.chooseSource(2)
            until { st.diffJob!!.error != null }
            assertTrue(st.diffJob!!.error!!.startsWith("No se pudo abrir c-rota"), st.diffJob!!.error)
            until { exists("diff-error") }
        } finally { st.dispose() }
    }
}
