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
import ksqlc.drivers.supportsModifyTable
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
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Modify dialog on a real SQL Server: what it offers for this engine, the preview, applying, and undoing everything on failure. */
@OptIn(ExperimentalTestApi::class)
class ModifySqlServerUiTest {
    companion object {
        private var mssql: MSSQLServerContainer? = null
        private fun url(db: String) = mssql!!.jdbcUrl.let { if ("encrypt" in it) it else "$it;encrypt=false" } + ";databaseName=$db"

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            mssql = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            DriverManager.getConnection(url("master"), mssql!!.username, mssql!!.password).use { it.createStatement().execute("create database ui") }
            DriverManager.getConnection(url("ui"), mssql!!.username, mssql!!.password).use { c ->
                for (t in listOf("t1", "t2", "t3")) {   // one table per test, so they do not depend on each other
                    c.createStatement().execute("create table dbo.$t(id int identity(1,1) not null primary key, name nvarchar(40) null)")
                    c.createStatement().execute("insert into dbo.$t(name) values (N'a')")
                }
            }
        }

        @AfterClass @JvmStatic fun stop() { mssql?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-modms"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-modmss")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 60_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}' error='${state.modifyError}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun frames() = repeat(3) { rule.mainClock.advanceTimeByFrame() }
    private fun columns(table: String) = DriverManager.getConnection(url("ui"), mssql!!.username, mssql!!.password).use { c ->
        c.createStatement().executeQuery("select name from sys.columns where object_id = object_id('dbo.$table') order by column_id")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun open(table: String) {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.MSSQL; f.host = mssql!!.host; f.portText = mssql!!.getMappedPort(1433).toString(); f.database = "ui"; f.user = mssql!!.username; f.password = mssql!!.password
        state.connect()
        until { state.explorer != null }
        state.openModify(DbObject(Namespace(null, "dbo"), table, DbObjectKind.TABLE))
        until { exists("modify-dialog") }
    }

    @Test fun theMenuIsOfferedForSqlServer() = assertTrue(supportsModifyTable(DbKind.MSSQL))

    @Test fun theDialogHidesWhatSqlServerLacksPreviewsAndApplies() {
        open("t1")
        rule.onNodeWithTag("modify:keys:0").performClick(); frames()
        assertFalse(exists("modify-key-deferrable"))                    // SQL Server has no deferrable keys
        rule.onNodeWithTag("modify:columns:0").performClick(); frames()
        rule.onNodeWithTag("modify-col-identity").assertIsNotEnabled()   // identity cannot be added to or taken off a column that exists
        assertFalse(exists("modify-col-always"))                         // no identity options
        rule.onNodeWithTag("modify:columns:1").performClick(); frames()
        rule.onNodeWithTag("modify-col-identity").assertIsNotEnabled()   // nor can it be added to any other column that exists
        rule.onNodeWithTag("modify-col-name").performTextReplacement("nombre"); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("sp_rename N'[dbo].[t1].[name]', N'nombre', N'COLUMN'", substring = true)
        assertFalse(state.modifyDraft!!.notice?.contains("cada sentencia") ?: false)   // all or nothing: no warning that each statement commits
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyDraft == null }
        assertEquals(listOf("id", "nombre"), columns("t1"))
    }

    @Test fun aNewColumnCanBeIdentityAndIndexesTakeIncludeAndConditionButNoCollation() {
        open("t2")
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()
        rule.onNodeWithTag("modify-col-identity").assertIsEnabled()      // a new column can be identity
        rule.onNodeWithTag("modify:indexes:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()
        rule.onNodeWithTag("modify:indexes:0").performClick(); frames()
        assertTrue(exists("modify-ix-inc:add") && exists("modify-ix-cond"))
        rule.onNodeWithTag("modify-ix-col:add").performClick(); frames()
        assertTrue(exists("modify-ix-col:add") && !exists("modify-ix-coll:0"))   // a column was added and has no collation field
    }

    @Test fun aFailureLeavesNothingAppliedAndTheDialogOpenWithTheError() {
        open("t3")
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()           // adds a column: statement 1
        rule.onNodeWithTag("modify:checks:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()
        rule.onNodeWithTag("modify:checks:0").performClick(); frames()
        rule.onNodeWithTag("modify-ck-name").performTextReplacement("never_ck"); frames()
        rule.onNodeWithTag("modify-ck-expr").performTextReplacement("1 = 0"); frames()     // the row there does not satisfy it: statement 2 fails
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyError != null }
        assertTrue(state.modifyError!!.contains("never_ck"), state.modifyError)
        assertFalse(state.modifyError!!.startsWith("Se aplicaron"))        // one transaction: no partial result to report
        assertEquals(listOf("id", "name"), columns("t3"))                  // the new column was undone with the rest
        assertTrue(exists("modify-error"))
    }
}
