package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
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
import kotlin.test.assertNull

/** The Modify dialog on a real PostgreSQL: edit, read the preview, apply, and see the table changed. */
@OptIn(ExperimentalTestApi::class)
class ModifyUiTest {
    companion object {
        private var pg: PostgreSQLContainer? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            pg = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
                c.createStatement().execute("create table public.mt(id int primary key, name text)")
            }
        }

        @AfterClass @JvmStatic fun stop() { pg?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-mod"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-mods")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 40_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun frames() = repeat(3) { rule.mainClock.advanceTimeByFrame() }
    private fun columns() = java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
        c.createStatement().executeQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'mt' order by ordinal_position")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun open() {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.POSTGRES; f.host = pg!!.host; f.portText = pg!!.firstMappedPort.toString(); f.database = pg!!.databaseName; f.user = pg!!.username; f.password = pg!!.password
        state.connect()
        until { state.explorer != null }
        state.openModify(DbObject(Namespace(null, "public"), "mt", DbObjectKind.TABLE))
        until { exists("modify-dialog") }
    }

    @Test fun renameAColumnAddAnotherSeeThePreviewAndApply() {
        open()
        rule.onNodeWithTag("modify-ok").assertIsNotEnabled()      // nothing changed yet
        rule.onNodeWithTag("modify:columns:1").performClick(); frames()
        rule.onNodeWithTag("modify-col-name").performTextReplacement("nombre"); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("rename column \"name\" to \"nombre\"", substring = true)
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("add column \"column_name\" integer", substring = true)
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyDraft == null }
        assertEquals(listOf("id", "nombre", "column_name"), columns())
        assertNull(state.modifyError)
    }

    @Test fun anInvalidModelDisablesOkAndShowsWhy() {
        open()
        rule.onNodeWithTag("modify:table:-1").performClick(); frames()
        rule.onNodeWithTag("modify-name").performTextReplacement(""); frames()
        rule.onNodeWithTag("modify-ok").assertIsNotEnabled()
        rule.onNodeWithTag("modify-preview").assertTextContains("necesita un nombre", substring = true)
        rule.onNodeWithTag("modify-cancel").performClick()
        until { state.modifyDraft == null }
    }
}
