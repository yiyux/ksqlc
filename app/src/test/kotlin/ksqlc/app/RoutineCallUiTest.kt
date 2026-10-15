package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DbKind
import ksqlc.core.Namespace
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Ejecutar…" on a routine of a real PostgreSQL: the parameters are asked for and the result opens as a tab. */
@OptIn(ExperimentalTestApi::class)
class RoutineCallUiTest {
    companion object {
        private var pg: PostgreSQLContainer? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            pg = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
                c.createStatement().execute("create function public.add(a int, b int) returns int language sql as 'select a + b'")
            }
        }

        @AfterClass @JvmStatic fun stop() { pg?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-rt"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-rts")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 40_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun theParametersAreAskedForAndTheResultOpens() {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.POSTGRES; f.host = pg!!.host; f.portText = pg!!.firstMappedPort.toString(); f.database = pg!!.databaseName; f.user = pg!!.username; f.password = pg!!.password
        state.connect()
        until { state.explorer != null }
        val add = state.explorer!!.source.objects(Namespace(null, "public")).first { it.name.startsWith("add(") }

        state.callRoutine(add)
        until { exists("param-dialog") }
        rule.onNodeWithTag("param:0").performTextReplacement("2")
        rule.onNodeWithTag("param:1").performTextReplacement("3")
        rule.onNodeWithTag("param-ok").performClick()
        until { !state.running && state.tabs.size == 1 }
        assertEquals(5, (state.tabs.single().rows.single().single() as Number).toInt())
    }
}
