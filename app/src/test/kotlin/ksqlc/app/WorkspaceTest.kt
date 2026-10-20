package ksqlc.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DataSource
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Several data sources, and several consoles on one of them, open at once (SQLite files, no window). */
class WorkspaceTest {
    private val ws = Workspace(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ws"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-wss")),
    )

    @After fun tearDown() = ws.dispose()

    private fun ds(name: String): DataSource = ConnectionForm().apply {
        this.name = name; currentId = name
        database = Files.createTempFile("ksqlc-ws", ".db").also { it.toFile().deleteOnExit() }.toString()
    }.toDataSource()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!cond()) { check(System.nanoTime() < end) { "timeout esperando: $what" }; Thread.sleep(50) }
    }

    private fun run(c: AppState, sql: String) {
        c.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        c.runSelectionOrCurrent()
        waitFor("terminar $sql") { !c.running }
    }

    @Test fun twoEnginesAndTwoConsolesOnOneStayOpenTogether() {
        val a = ds("a"); val b = ds("b")
        ws.open(a)                       // the idle first console takes it
        assertEquals(1, ws.consoles.size)
        ws.open(b)                       // the first is busy now: a second console
        waitFor("esquema de a") { ws.consoles[0].explorer != null }
        waitFor("esquema de b") { ws.consoles[1].explorer != null }
        assertEquals(2, ws.consoles.size)
        ws.newConsole(a)                 // a second console on the same data source
        assertEquals(3, ws.consoles.size)
        assertEquals(listOf("console [a]", "console [b]", "console 2 [a]"), ws.consoles.indices.map(ws::title))

        run(ws.consoles[0], "create table t1(x integer)")
        run(ws.consoles[1], "select 1")
        run(ws.consoles[2], "select 2")
        assertNotSame(ws.consoles[0].consoleSession, ws.consoles[2].consoleSession)
        assertTrue(ws.consoles.all { it.consoleSession != null })

        ws.open(b)                       // already open: just shown
        assertEquals(1, ws.activeIndex)
        ws.closeConsole(2)
        assertEquals(2, ws.consoles.size)
        assertEquals(1, ws.activeIndex)
        assertTrue(ws.consoles[0].consoleSession != null && ws.consoles[1].consoleSession != null)
    }
}
