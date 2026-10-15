package ksqlc.drivers.server

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.*

/** A real PostgreSQL that only an sshd (OpenSSH on Alpine) can reach, and the tunnel through it: password and key login, host keys. */
class SshTunnelServerTest {
    companion object {
        private var network: Network? = null
        private var pg: PostgreSQLContainer? = null
        private var sshd: GenericContainer<*>? = null
        private lateinit var keyFile: String
        private val passphrase = "frase-secreta"

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val net = Network.newNetwork().also { network = it }
            pg = PostgreSQLContainer("postgres:16-alpine").withNetwork(net).withNetworkAliases("db").also { it.start() }
            val script = "apk add --no-cache openssh >/dev/null && ssh-keygen -A >/dev/null && adduser -D tun && echo 'tun:secret' | chpasswd && " +
                "sed -i 's/^#\\?AllowTcpForwarding.*/AllowTcpForwarding yes/; s/^#\\?PasswordAuthentication.*/PasswordAuthentication yes/; s/^#\\?UsePAM.*/UsePAM no/' /etc/ssh/sshd_config && " +
                "exec /usr/sbin/sshd -D -e"
            val box = GenericContainer("alpine:3.20").withNetwork(net).withExposedPorts(22).withCommand("sh", "-c", script)
                .waitingFor(Wait.forLogMessage(".*Server listening on 0.0.0.0 port 22.*", 1).withStartupTimeout(java.time.Duration.ofMinutes(3)))
            box.start(); sshd = box
            // a key pair protected by a passphrase, its public half authorised for the user
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
            val dir = Files.createTempDirectory("ksqlc-ssh")
            val priv = dir.resolve("id_rsa"); pair.writePrivateKey(priv.toString(), passphrase.toByteArray())
            keyFile = priv.toString()
            val pub = java.io.ByteArrayOutputStream().also { pair.writePublicKey(it, "ksqlc-test") }.toString()
            box.execInContainer("sh", "-c", "mkdir -p /home/tun/.ssh && echo '${pub.trim()}' > /home/tun/.ssh/authorized_keys && chmod 700 /home/tun/.ssh && chmod 600 /home/tun/.ssh/authorized_keys && chown -R tun:tun /home/tun/.ssh && passwd -u tun >/dev/null 2>&1 || true")
        }

        @AfterClass @JvmStatic fun stop() { Tunnels.closeAll(); sshd?.stop(); pg?.stop(); network?.close() }
    }

    private val known = Files.createTempDirectory("ksqlc-known").resolve("known_hosts")

    @Before fun setUp() { Tunnels.closeAll(); Tunnels.knownHosts = known; Tunnels.hostKeyDecider = HostKeyDecider { true } }
    @After fun reset() { Tunnels.closeAll(); Tunnels.hostKeyDecider = null }

    private fun ssh(auth: SshAuth = SshAuth.PASSWORD, user: String = "tun") = SshSettings(true, sshd!!.host, sshd!!.getMappedPort(22), user, auth, if (auth == SshAuth.KEY) keyFile else "")
    // the database as seen from the jump host: its alias on the shared network and the port inside it
    private fun ds(ssh: SshSettings, secret: String?) = DataSource(
        "t", "t", DbKind.POSTGRES, JdbcUrl.build(DbKind.POSTGRES, "db", 5432, pg!!.databaseName, false), pg!!.username, ssh = ssh, sshSecret = secret,
    )
    private fun one(ds: DataSource) = openConnection(ds, pg!!.password).use { c -> c.createStatement().executeQuery("select 1").let { it.next(); it.getInt(1) } }

    @Test fun reachesADatabaseThatIsOnlyVisibleFromTheJumpHostWithAPassword() {
        assertEquals(1, one(ds(ssh(), "secret")))
    }

    @Test fun reachesItWithAPassphraseProtectedKey() {
        assertEquals(1, one(ds(ssh(SshAuth.KEY), passphrase)))
    }

    @Test fun theSameTunnelServesSeveralConnections() {
        val d = ds(ssh(), "secret")
        assertEquals(1, one(d)); assertEquals(1, one(d))
        assertEquals(Tunnels.forward(d.ssh, "secret", "db", 5432), Tunnels.forward(d.ssh, "secret", "db", 5432))
    }

    @Test fun aWrongPasswordAndAWrongPassphraseAreReported() {
        val e = assertFailsWith<java.sql.SQLException> { one(ds(ssh(), "nope")) }
        assertTrue("rechazó las credenciales" in e.message.orEmpty(), e.message)
        assertFailsWith<java.sql.SQLException> { one(ds(ssh(SshAuth.KEY), "otra-frase")) }
    }

    @Test fun aNewHostIsAskedAboutOnceThenRemembered() {
        var asked = 0
        Tunnels.hostKeyDecider = HostKeyDecider { asked++; true }
        one(ds(ssh(), "secret")); Tunnels.closeAll(); one(ds(ssh(), "secret"))
        assertEquals(1, asked)
        assertTrue(Files.readString(known).isNotBlank(), "known_hosts")
    }

    @Test fun refusingTheHostKeyStopsTheConnection() {
        Tunnels.hostKeyDecider = HostKeyDecider { false }
        val e = assertFailsWith<java.sql.SQLException> { one(ds(ssh(), "secret")) }
        assertTrue("clave del servidor" in e.message.orEmpty(), e.message)
    }

    @Test fun aChangedHostKeyIsRefused() {
        one(ds(ssh(), "secret")); Tunnels.closeAll()
        // same host, port and key type, but another key: what a man in the middle would look like
        val tampered = Files.readString(known).lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
            val parts = line.split(" ").toMutableList()
            val other = when (parts[1]) {
                "ssh-ed25519" -> KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
                "ssh-rsa" -> KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
                else -> KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
            }
            parts[2] = java.util.Base64.getEncoder().encodeToString(other.publicKeyBlob); parts.joinToString(" ")
        }
        Files.writeString(known, tampered + "\n")
        var asked = 0
        Tunnels.hostKeyDecider = HostKeyDecider { asked++; true }      // even a decider that says yes must not be asked
        val e = assertFailsWith<java.sql.SQLException> { one(ds(ssh(), "secret")) }
        assertEquals(0, asked)
        assertTrue("ha cambiado" in e.message.orEmpty(), e.message)
    }

    @Test fun aManualUrlCannotBeTunnelled() {
        val manual = ds(ssh(), "secret").copy(url = "jdbc:postgresql://db:5432/x?currentSchema=a")
        val e = assertFailsWith<java.sql.SQLException> { openConnection(manual, "x") }
        assertTrue("URL manual" in e.message.orEmpty(), e.message)
    }

    @Test fun sqliteIgnoresTheTunnel() {
        val file = Files.createTempFile("ksqlc-ssh", ".db").also { it.toFile().deleteOnExit() }.toString()
        val d = DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite:$file", ssh = SshSettings(true, "unused.invalid"))
        openConnection(d, null).use { assertFalse(it.isClosed) }
    }
}
