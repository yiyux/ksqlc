package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.core.SshAuth
import ksqlc.core.SshSettings
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

class ConnectionFormOptionsTest {
    @Test fun optionsRoundTripThroughTheFormAndOnlyKeepWhatTheEngineOffers() {
        val f = ConnectionForm().apply { kind = DbKind.SQLITE; timeZone = "UTC"; singleDatabase = true }
        assertEquals("", f.options.timeZone); assertEquals(false, f.options.singleDatabase)      // SQLite has neither
        f.kind = DbKind.MYSQL; f.host = "h"; f.portText = "3306"; f.database = "app"
        f.readOnly = true; f.timeZone = " +01:00 "; f.keepAliveOn = true; f.keepAliveText = "90"; f.autoDisconnectOn = true; f.autoDisconnectText = "2"
        f.singleDatabase = true; f.startupScript = "set names utf8mb4"; f.autoSync = false; f.introspectionText = "15"
        val o = f.options
        assertEquals(ConnectionOptions(true, "+01:00", 90, 5, true, "set names utf8mb4", false, 15), o)      // 2 s is raised to the 5 s minimum
        val copy = ConnectionForm().also { it.load(f.toDataSource().copy(id = "x")) }
        assertEquals(o, copy.options)
        f.keepAliveOn = false; f.introspectionText = "abc"
        assertEquals(0, f.options.keepAliveSeconds); assertEquals(0, f.options.introspectionMinutes)
        assertEquals(ConnectionOptions(), ConnectionForm().options)
    }
}

class ConnectionFormSshTest {
    @Test fun theTunnelRoundTripsThroughTheFormAndOnlyAppliesWhereItCan() {
        val f = ConnectionForm().apply {
            kind = DbKind.POSTGRES; host = "db"; portText = "5432"
            sshEnabled = true; sshHost = " jump "; sshPortText = "2222"; sshUser = "me"; sshAuth = SshAuth.KEY; sshKeyFile = "~/k"; sshSecret = "frase"
        }
        val ds = f.toDataSource()
        assertEquals(SshSettings(true, "jump", 2222, "me", SshAuth.KEY, "~/k"), ds.ssh); assertEquals("frase", ds.sshSecret)
        val back = ConnectionForm().also { it.load(ds.copy(id = "x")) }
        assertEquals(ds.ssh, back.ssh); assertEquals("", back.sshSecret)      // the secret is fetched from the keychain separately
        f.kind = DbKind.SQLITE; assertEquals(false, f.ssh.enabled)
        f.kind = DbKind.POSTGRES; f.customUrl = true; assertEquals(false, f.ssh.enabled)
        assertEquals(SshSettings(), ConnectionForm().ssh)
    }
}

@OptIn(ExperimentalTestApi::class)
class DataSourceOptionsUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-o"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-os")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun frames(n: Int = 5) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        frames(2)
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun pickKind(kind: DbKind) {
        rule.onNodeWithTag("ds-tab:0").performClick(); frames()
        rule.onNodeWithTag("ds-kind").performClick(); until { exists("kind:${kind.name}") }
        rule.onNodeWithTag("kind:${kind.name}").performClick(); until { !exists("kind:${kind.name}") }
        rule.mainClock.advanceTimeBy(800)
        // the first click after a menu closes can still be spent on the menu's own dismissal
        repeat(3) { if (!exists("ds-readonly")) { rule.onNodeWithTag("ds-tab:1").performClick(); frames(); rule.mainClock.advanceTimeBy(300) } }
        until { exists("ds-readonly") }
    }
    private fun offered(vararg tags: String) = tags.filter { exists(it) }.toSet()

    @Test fun eachEngineGetsOnlyTheOptionsItCanHonour() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick(); until { exists("datasource-dialog") }
        rule.onNodeWithTag("ds-tab:1").performClick(); frames()
        val optional = arrayOf("ds-tx:0", "ds-tz", "ds-single")
        val always = arrayOf("ds-readonly", "ds-keepalive", "ds-autodisconnect", "ds-startup", "ds-autosync", "ds-introspect")
        assertEquals(always.toSet(), offered(*always))
        assertEquals(setOf("ds-tx:0"), offered(*optional))      // SQLite, the form's default
        pickKind(DbKind.POSTGRES); assertEquals(setOf("ds-tx:0", "ds-tz"), offered(*optional))
        pickKind(DbKind.MYSQL); assertEquals(setOf("ds-tx:0", "ds-tz", "ds-single"), offered(*optional))
        pickKind(DbKind.SQLITE); assertEquals(setOf("ds-tx:0"), offered(*optional))
        pickKind(DbKind.MSSQL); assertEquals(setOf("ds-tx:0"), offered(*optional))
        pickKind(DbKind.ORACLE); assertEquals(setOf("ds-tx:0", "ds-tz", "ds-single"), offered(*optional))
        pickKind(DbKind.CASSANDRA); assertEquals(setOf("ds-single"), offered(*optional))
        assertEquals(always.toSet(), offered(*always))
    }

    @Test fun savedOptionsApplyToTheConnection() {
        rule.setContent { App(state) }
        val db = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { it.createStatement().execute("create table t(id integer primary key, v text)") }
        rule.onNodeWithContentDescription("Nuevo data source").performClick(); until { exists("datasource-dialog") }
        rule.onNodeWithTag("ds-name").performTextClearance(); rule.onNodeWithTag("ds-name").performTextInput("opciones")
        pickKind(DbKind.SQLITE)
        rule.onNodeWithTag("ds-tab:0").performClick(); frames()
        rule.onNodeWithTag("ds-db").performTextInput(db)
        rule.onNodeWithTag("ds-tab:1").performClick(); frames()
        rule.onNodeWithTag("ds-readonly").performClick(); frames()
        rule.onNodeWithTag("ds-startup").performTextInput("create temp table boot(x)")
        rule.onNodeWithTag("ds-keepalive").performClick(); frames()
        rule.onNodeWithTag("ds-keepalive-sec").performTextReplacement("120")
        rule.onNodeWithTag("ds-autosync").performClick(); frames()
        rule.onNodeWithTag("ds-ok").performClick()
        until { !exists("datasource-dialog") && state.saved.size == 1 }
        val o = state.saved.single().options
        assertEquals(ConnectionOptions(readOnly = true, keepAliveSeconds = 120, startupScript = "create temp table boot(x)", autoSync = false), o)
        assertEquals(o, state.form.options)

        // the connection honours them: reads work, writes are refused with the reason, table tabs cannot be edited
        until { state.explorer != null }
        state.onEditorChange(TextFieldValue("select 1", TextRange(8)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        state.onEditorChange(TextFieldValue("insert into t values (1, 'x')", TextRange(5)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.log.any { "solo lectura" in it } }
        state.openData(DbObject(Namespace(null, "main"), "t", DbObjectKind.TABLE))
        until { state.dataTabs.size == 1 }
        assertTrue(!state.dataTabs[0].editor.canInsert); assertEquals("La conexión es de solo lectura", state.dataTabs[0].editor.readOnlyReason())
    }

    @Test fun theSshTabSavesTheTunnelAndKeepsItsSecretOutOfTheFile() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick(); until { exists("datasource-dialog") }
        pickKind(DbKind.POSTGRES)
        rule.onNodeWithTag("ds-tab:2").performClick(); until { exists("ds-ssh-on") }
        rule.onNodeWithTag("ds-ssh-host").assertIsNotEnabled()      // nothing to type until the tunnel is switched on
        rule.onNodeWithTag("ds-ssh-on").performClick(); frames()
        rule.onNodeWithTag("ds-ssh-host").performTextInput("jump.example")
        rule.onNodeWithTag("ds-ssh-port").performTextReplacement("2200")
        rule.onNodeWithTag("ds-ssh-user").performTextInput("me")
        rule.onNodeWithTag("ds-ssh-auth:1").performClick(); until { exists("ds-ssh-key") }
        rule.onNodeWithTag("ds-ssh-key").performTextInput("~/.ssh/id_ed25519")
        rule.onNodeWithTag("ds-ssh-secret").performTextInput("la-frase")
        rule.onNodeWithTag("ds-apply").performClick()
        until { state.saved.size == 1 }
        assertEquals(SshSettings(true, "jump.example", 2200, "me", SshAuth.KEY, "~/.ssh/id_ed25519"), state.saved.single().ssh)
        assertEquals("la-frase", state.storedSshSecret(state.saved.single().id))
        assertEquals(null, state.saved.single().sshSecret)
    }

    @Test fun theSshTabExplainsWhenThereIsNothingToTunnel() {
        rule.setContent { App(state) }
        rule.onNodeWithContentDescription("Nuevo data source").performClick(); until { exists("datasource-dialog") }
        rule.onNodeWithTag("ds-tab:2").performClick(); until { exists("datasource-dialog") && !exists("ds-ssh-on") }      // SQLite is the form's default
        rule.onNodeWithText("SQLite abre un archivo local", substring = true).assertExists()
    }
}
