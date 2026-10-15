package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DbKind
import ksqlc.core.SshAuth
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app opening a database only an SSH jump host can reach: the host-key question, then the schema through the tunnel. */
@OptIn(ExperimentalTestApi::class)
class SshTunnelUiTest {
    companion object {
        private var network: Network? = null
        private var pg: PostgreSQLContainer? = null
        private var sshd: GenericContainer<*>? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val net = Network.newNetwork().also { network = it }
            pg = PostgreSQLContainer("postgres:16-alpine").withNetwork(net).withNetworkAliases("db").also { it.start() }
            val script = "apk add --no-cache openssh >/dev/null && ssh-keygen -A >/dev/null && adduser -D tun && echo 'tun:secret' | chpasswd && " +
                "sed -i 's/^#\\?AllowTcpForwarding.*/AllowTcpForwarding yes/; s/^#\\?PasswordAuthentication.*/PasswordAuthentication yes/; s/^#\\?UsePAM.*/UsePAM no/' /etc/ssh/sshd_config && exec /usr/sbin/sshd -D -e"
            sshd = GenericContainer("alpine:3.20").withNetwork(net).withExposedPorts(22).withCommand("sh", "-c", script)
                .waitingFor(Wait.forLogMessage(".*Server listening on 0.0.0.0 port 22.*", 1).withStartupTimeout(java.time.Duration.ofMinutes(3))).also { it.start() }
        }

        @AfterClass @JvmStatic fun stop() { ksqlc.drivers.Tunnels.closeAll(); sshd?.stop(); pg?.stop(); network?.close() }
    }

    @get:Rule val rule = createComposeRule()

    private val known = Files.createTempDirectory("ksqlc-known").resolve("known_hosts")
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-ssh"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-sshs")),
    )

    @Before fun setUp() { ksqlc.drivers.Tunnels.closeAll(); ksqlc.drivers.Tunnels.knownHosts = known; rule.mainClock.autoAdvance = false }
    @After fun tearDown() { state.dispose() }

    private fun until(timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }

    private fun configure() {
        val f = state.form
        f.kind = DbKind.POSTGRES; f.host = "db"; f.portText = "5432"; f.database = pg!!.databaseName; f.user = pg!!.username; f.password = pg!!.password
        f.sshEnabled = true; f.sshHost = sshd!!.host; f.sshPortText = sshd!!.getMappedPort(22).toString(); f.sshUser = "tun"; f.sshAuth = SshAuth.PASSWORD; f.sshSecret = "secret"
    }

    @Test fun anUnknownHostIsAskedAboutThenTheSchemaLoadsThroughTheTunnel() {
        rule.setContent { App(state) }
        configure()
        state.connect()
        until { state.confirmation?.title == "Servidor SSH desconocido" }
        rule.onNodeWithTag("confirm-ok").performClick()
        until { state.explorer != null }
        assertEquals("public", state.explorer!!.source.namespaces().first { it.label == "public" }.label)
        assertTrue(Files.readString(known).isNotBlank())

        // a query goes through the same tunnel
        state.onEditorChange(androidx.compose.ui.text.input.TextFieldValue("select 41 + 1", androidx.compose.ui.text.TextRange(5)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        assertEquals("42", state.tabs[0].rows.single().single().toString())
    }

    @Test fun refusingTheHostKeyLeavesTheConnectionClosedWithTheReason() {
        rule.setContent { App(state) }
        configure()
        state.connect()
        until { state.confirmation?.title == "Servidor SSH desconocido" }
        rule.onNodeWithTag("confirm-cancel").performClick()
        until { !state.connecting && state.explorer == null && "túnel SSH" in state.status }
        assertNotNull(state.status)
    }
}
