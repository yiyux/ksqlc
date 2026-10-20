package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import ksqlc.core.*
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
class CopyTableUiTest {
    @get:Rule val rule = createComposeRule()

    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-ui-copy", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { it.runScript(sql.toList()) }
        return f
    }
    private val bFile = db("create table other(id integer primary key)")
    private val store = ConnectionStore(Files.createTempDirectory("ksqlc-ui-cp"), InMemorySecretStore()).also {
        it.save(DataSource("a", "a-origen", DbKind.SQLITE, "jdbc:sqlite:" + db("create table t(id integer primary key, v text not null)", "insert into t values (1,'a'),(2,'b'),(3,'c')")), null)
        it.save(DataSource("b", "b-destino", DbKind.SQLITE, "jdbc:sqlite:$bFile"), null)
        it.save(DataSource("c", "c-solo-lectura", DbKind.SQLITE, "jdbc:sqlite:$bFile", options = ConnectionOptions(readOnly = true)), null)
    }
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-cps")))

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}' error=${state.copyTableJob?.error})" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun text(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun opened(source: String): CopyTableJob {
        rule.setContent { App(state) }
        until { state.form.currentId == "a" }
        state.connect(); until { state.explorer != null }
        state.openCopyTable(DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE))
        until { exists("copy-table-dialog") }
        val job = state.copyTableJob!!
        job.picker.chooseSource(job.sources.indexOfFirst { it.id == source })
        until { job.picker.namespaces.isNotEmpty() && job.picker.tables.isNotEmpty() }
        return job
    }
    private fun rowsOf(table: String): List<String> = java.sql.DriverManager.getConnection("jdbc:sqlite:$bFile").use { c ->
        c.createStatement().executeQuery("select id || ':' || v from $table order by id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test fun createsTheTableInAnotherConnectionAndCopiesTheRows() {
        val job = opened("b")
        until { exists("copy-table-hint") }
        assertEquals("Se creará la tabla t", text("copy-table-hint"))
        rule.onNodeWithTag("copy-table-run").performClick()
        until { exists("confirm-ok") }
        rule.onNodeWithTag("confirm-ok").performClick()
        until { job.message != null || job.error != null }
        assertEquals(null, job.error)
        assertEquals(listOf("1:a", "2:b", "3:c"), rowsOf("t"))
        assertTrue(text("copy-table-done").contains("Copiadas 3 filas"), text("copy-table-done"))
        // the table now exists, so a second copy would add to it
        until { job.existing != null }
        assertEquals("Se añadirán las filas a t", text("copy-table-hint"))
    }

    @Test fun aReadOnlyTargetCannotBeCopiedTo() {
        val job = opened("c")
        until { exists("copy-table-hint") }
        assertTrue(text("copy-table-hint").contains("solo lectura"), text("copy-table-hint"))
        assertTrue(job.blocked)
        rule.onNodeWithTag("copy-table-run").assertIsNotEnabled()
    }
}
