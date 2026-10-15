package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.drivers.CellValue
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app against Cassandra: a running node given by `KSQLC_CASSANDRA=host:port`, else a Docker container (skipped without either). */
@OptIn(ExperimentalTestApi::class)
class CassandraUiTest {
    @get:Rule val rule = createComposeRule()

    private var node: String? = System.getenv("KSQLC_CASSANDRA")
    private var container: org.testcontainers.cassandra.CassandraContainer? = null
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-c"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-cs")),
    )

    @Before fun setUp() {
        if (node == null) {
            assumeTrue("Docker no disponible y KSQLC_CASSANDRA no definido", runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = org.testcontainers.cassandra.CassandraContainer("cassandra:5.0").also { it.start() }
            container = c; node = "${c.contactPoint.hostString}:${c.contactPoint.port}"
        }
        rule.mainClock.autoAdvance = false
    }
    @After fun tearDown() { state.dispose(); container?.stop() }

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 60_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun open(keyspace: String = "") = ksqlc.drivers.openConnection(DataSource("t", "t", DbKind.CASSANDRA, "jdbc:cassandra://$node${if (keyspace.isEmpty()) "" else "/$keyspace"}?localdatacenter=datacenter1"), null)
    private fun exec(cql: String) = open().use { it.createStatement().execute(cql) }
    private fun names(): List<String> = open("ksqlc_ui").use { c ->
        c.createStatement().executeQuery("select name from people").let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }.sorted()
    }

    @Test fun connectsBrowsesAndEditsATable() {
        exec("drop keyspace if exists ksqlc_ui")
        exec("create keyspace ksqlc_ui with replication = {'class': 'SimpleStrategy', 'replication_factor': 1}")
        exec("create table ksqlc_ui.people(id int primary key, name text)")
        exec("insert into ksqlc_ui.people (id, name) values (1, 'Ada')")
        exec("insert into ksqlc_ui.people (id, name) values (2, 'Grace')")

        rule.setContent { App(state) }
        val (host, port) = node!!.split(":")
        state.form.kind = DbKind.CASSANDRA; state.form.host = host; state.form.portText = port; state.form.database = "ksqlc_ui"; state.form.datacenter = "datacenter1"
        assertEquals("jdbc:cassandra://$node/ksqlc_ui?localdatacenter=datacenter1", state.form.url)
        rule.onAllNodesWithTag("tx-mode:console").assertCountEquals(0)      // no transactions in Cassandra

        state.onEditorChange(TextFieldValue("select * from people", TextRange(5)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 && state.explorer != null }
        assertEquals(listOf("id", "name"), state.tabs[0].result.columns.map { it.name })
        assertEquals(2, state.tabs[0].rows.size)

        until { state.catalog?.all()?.any { it.name == "people" } == true }
        state.openData(DbObject(Namespace(null, "ksqlc_ui"), "people", DbObjectKind.TABLE))
        until { state.dataTabs.size == 1 }
        val data = state.dataTabs[0]
        data.editor.setCell(1, 1, CellValue.Text("Hopper"), data.grid)
        until { data.editor.hasChanges }
        state.submitChanges(data)
        until { !data.editor.hasChanges && !data.loading }
        assertTrue("Error" !in state.status, state.status)
        assertEquals(listOf("Ada", "Hopper"), names())
    }
}
