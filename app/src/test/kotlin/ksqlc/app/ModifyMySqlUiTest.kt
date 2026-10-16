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
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Modify dialog on a real MySQL: edit, read the preview, apply, and see the table changed. */
@OptIn(ExperimentalTestApi::class)
class ModifyMySqlUiTest {
    companion object {
        private var mysql: MySQLContainer? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            mysql = MySQLContainer("mysql:8.4").also { it.start() }
            java.sql.DriverManager.getConnection(mysql!!.jdbcUrl, "root", mysql!!.password).use { c ->
                c.createStatement().execute("create database ui")
                for (t in listOf("t1", "t2")) {   // one table per test, so they do not depend on each other
                    c.createStatement().execute("create table ui.$t(id int not null auto_increment primary key, name varchar(40))")
                    c.createStatement().execute("insert into ui.$t(name) values ('a')")
                }
            }
        }

        @AfterClass @JvmStatic fun stop() { mysql?.stop() }
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
    private fun columns(table: String) = java.sql.DriverManager.getConnection(mysql!!.jdbcUrl, "root", mysql!!.password).use { c ->
        c.createStatement().executeQuery("select column_name from information_schema.columns where table_schema = 'ui' and table_name = '$table' order by ordinal_position")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun open(table: String) {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.MYSQL; f.host = mysql!!.host; f.portText = mysql!!.firstMappedPort.toString(); f.database = "ui"; f.user = "root"; f.password = mysql!!.password
        state.connect()
        until { state.explorer != null }
        state.openModify(DbObject(Namespace("ui", null), table, DbObjectKind.TABLE))
        until { exists("modify-dialog") }
    }

    @Test fun theDialogShowsTheNoticeHidesPostgresFieldsAndApplies() {
        open("t1")
        rule.onNodeWithTag("modify:keys:0").performClick(); frames()
        assertFalse(exists("modify-key-deferrable"))               // MySQL has no deferrable keys
        rule.onNodeWithTag("modify:columns:1").performClick(); frames()
        rule.onNodeWithTag("modify-col-name").performTextReplacement("nombre"); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("cada sentencia", substring = true)
        rule.onNodeWithTag("modify-preview").assertTextContains("rename column `name` to `nombre`", substring = true)
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyDraft == null }
        assertEquals(listOf("id", "nombre"), columns("t1"))
    }

    @Test fun aFailureInTheMiddleReloadsTheDialogWithTheErrorAndWhatWasApplied() {
        open("t2")
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()           // adds a column: statement 1
        rule.onNodeWithTag("modify:checks:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()           // adds a check
        rule.onNodeWithTag("modify:checks:0").performClick(); frames()
        rule.onNodeWithTag("modify-ck-name").performTextReplacement("mt_never_ck"); frames()
        rule.onNodeWithTag("modify-ck-expr").performTextReplacement("1 = 0"); frames()     // the row there is does not satisfy it: statement 2 fails
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyError != null }
        assertTrue(state.modifyError!!.startsWith("Se aplicaron 1 de 2"), state.modifyError)
        until { state.modifyDraft!!.before.columns.size == 3 }              // reloaded from the server: the new column is there
        assertTrue(exists("modify-error"))
    }
}
