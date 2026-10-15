package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DbKind
import ksqlc.core.SchemaFilter
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

/** The Schemas tab against a real PostgreSQL: read the server's schemas, choose some, and see the explorer follow. */
@OptIn(ExperimentalTestApi::class)
class SchemasTabUiTest {
    companion object {
        private var pg: PostgreSQLContainer? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            pg = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
                listOf("create schema app", "create schema ventas", "create schema other").forEach { c.createStatement().execute(it) }
            }
        }

        @AfterClass @JvmStatic fun stop() { pg?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-sch"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-schs")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun frames(n: Int = 5) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 40_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        frames(2)
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun listed() = state.explorer!!.source.namespaces().map { it.label }

    @Test fun chosenSchemasAndPatternsDecideWhatTheExplorerLists() {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.POSTGRES; f.host = pg!!.host; f.portText = pg!!.firstMappedPort.toString(); f.database = pg!!.databaseName; f.user = pg!!.username; f.password = pg!!.password
        state.dataSourceDialog = true
        until { exists("datasource-dialog") }

        // read what the server has and choose one
        rule.onNodeWithTag("ds-tab:3").performClick(); until { exists("ds-schemas-load") }
        rule.onNodeWithTag("ds-schemas-load").performClick()
        until { exists("ds-schema:app") && exists("ds-schema:pg_catalog") }      // system schemas are on offer too
        rule.onNodeWithTag("ds-schema:app").assertIsNotEnabled()      // choosing needs "solo los elegidos"
        rule.onNodeWithTag("ds-schemas-mode:1").performClick(); frames()
        rule.onNodeWithTag("ds-schema:app").assertIsEnabled().performClick(); frames()
        rule.onNodeWithTag("ds-ok").performClick()
        until { !exists("datasource-dialog") && state.explorer != null && listed() == listOf("app") }
        assertEquals(SchemaFilter(false, listOf("app")), state.saved.single().schemas)
        assertEquals(listOf("app"), state.catalog!!.schemas())      // the editor's catalog follows

        // a pattern instead, back on "todos"
        state.dataSourceDialog = true; until { exists("datasource-dialog") }
        rule.onNodeWithTag("ds-tab:3").performClick(); until { exists("ds-schemas-mode:0") }
        rule.onNodeWithTag("ds-schemas-mode:0").performClick(); frames()
        rule.onNodeWithTag("ds-schema-pattern").performTextInput("ven*, oth*")
        rule.onNodeWithTag("ds-ok").performClick()
        until { !exists("datasource-dialog") && state.explorer != null && listed().toSet() == setOf("ventas", "other") }
        assertEquals("ven*, oth*", state.saved.single().schemas.pattern)
    }
}
