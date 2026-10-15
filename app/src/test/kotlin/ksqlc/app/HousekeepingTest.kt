package ksqlc.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** The timers and switches of the connection options, against a real SQLite file and no window. */
class HousekeepingTest {
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-hk"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-hks")),
    )

    @After fun tearDown() = state.dispose()

    private fun waitFor(what: String, seconds: Int = 20, cond: () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (!cond()) { check(System.nanoTime() < end) { "timeout esperando: $what (status='${state.status}')" }; Thread.sleep(50) }
    }

    private fun run(sql: String) {
        waitFor("que la consola esté libre") { !state.running }
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        state.runSelectionOrCurrent()
        waitFor("terminar $sql") { !state.running }
    }

    private fun connect(configure: ConnectionForm.() -> Unit = {}) {
        state.form.database = Files.createTempFile("ksqlc-hk", ".db").also { it.toFile().deleteOnExit() }.toString()
        state.form.configure()
        run("select 1")
        waitFor("el esquema") { state.catalog != null }
    }

    @Test fun keepAliveRunsOnAnIdleConnection() {
        connect { keepAliveOn = true; keepAliveText = "5" }
        waitFor("una consulta de keep-alive", seconds = 15) { (state.consoleSession?.keepAlives ?: 0) >= 1 }
    }

    @Test fun anIdleConsoleConnectionIsClosedAndComesBackOnTheNextRun() {
        connect { autoDisconnectOn = true; autoDisconnectText = "5" }
        assertTrue(state.consoleSession != null)
        waitFor("cerrar la conexión por inactividad", seconds = 15) { state.consoleSession == null }
        assertTrue("inactividad" in state.status, state.status)
        run("select 2")
        assertTrue(state.consoleSession != null)
        assertEquals("2", state.tabs.single().rows.single().single().toString())
    }

    @Test fun withoutAutoSyncADdlDoesNotRefreshTheExplorerUntilAsked() {
        connect { autoSync = false }
        run("create table made_later(id integer)")
        Thread.sleep(1500)
        assertTrue(state.catalog!!.all().none { it.name == "made_later" })
        state.refreshExplorer()
        waitFor("el esquema nuevo") { state.catalog?.all()?.any { it.name == "made_later" } == true }
    }

    @Test fun withAutoSyncADdlRefreshesTheExplorerByItself() {
        connect()
        run("create table made_now(id integer)")
        waitFor("el esquema nuevo") { state.catalog?.all()?.any { it.name == "made_now" } == true }
    }

    @Test fun theIntrospectionIntervalReadsTheSchemaAgain() {
        state.introspectionUnitMillis = 100
        connect { introspectionText = "1" }
        val first = state.catalog
        waitFor("otra lectura del esquema", seconds = 15) { state.catalog !== first && state.catalog != null }
        assertNotSame(first, state.catalog)
    }

    @Test fun noOptionsMeansNoTimers() {
        connect()
        Thread.sleep(3000)
        assertEquals(0, state.consoleSession?.keepAlives)
        assertTrue(state.consoleSession != null)
    }
}
