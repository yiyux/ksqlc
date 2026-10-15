package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.images.builder.Transferable
import org.testcontainers.mariadb.MariaDBContainer as MariaDb
import org.testcontainers.mysql.MySQLContainer as MySql
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import kotlin.test.*

/**
 * Real servers with TLS on and certificates made for the test: each verification level, a wrong CA, a wrong host name, and client certificates.
 * The server certificate is valid for `localhost` only, so a connection to 127.0.0.1 shows what "verify name" adds over "verify CA".
 */
class SslServerTest {
    companion object {
        private lateinit var certs: TestCerts
        private var pg: PostgreSQLContainer? = null
        private var pgClientCert: PostgreSQLContainer? = null
        private var mysql: MySql? = null
        private var maria: MariaDb? = null

        private fun postgres(requireClientCert: Boolean): PostgreSQLContainer {
            val hba = "local all all trust\nhostssl all all all scram-sha-256${if (requireClientCert) " clientcert=verify-ca" else ""}\nhostnossl all all all reject\n"
            val dir = "/var/lib/postgresql/tls"
            val script = "mkdir -p $dir && cp /certs/* $dir/ && chown -R postgres $dir && chmod 600 $dir/server.key && exec docker-entrypoint.sh postgres -c fsync=off " +
                "-c ssl=on -c ssl_cert_file=$dir/server.crt -c ssl_key_file=$dir/server.key -c ssl_ca_file=$dir/ca.crt -c hba_file=$dir/pg_hba.conf"
            return PostgreSQLContainer("postgres:16-alpine")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.crt")), "/certs/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.key")), "/certs/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("ca.crt")), "/certs/ca.crt")
                .withCopyToContainer(Transferable.of(hba), "/certs/pg_hba.conf")
                .withCreateContainerCmdModifier { it.withEntrypoint("sh") }.withCommand("-c", script)
                .also { it.start() }
        }


        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            certs = TestCerts()
            certs.authority(); certs.authority("other"); certs.issue("server", "localhost", "DNS:localhost"); certs.issue("client", "tester")
            pg = postgres(false)
            pgClientCert = postgres(true)
            val tls = "/etc/tls"
            fun script(daemon: String, user: String) = "mkdir -p $tls && cp /certs/* $tls/ && chown -R $user $tls && chmod 600 $tls/server.key && exec docker-entrypoint.sh $daemon --ssl-ca=$tls/ca.crt --ssl-cert=$tls/server.crt --ssl-key=$tls/server.key"
            mysql = MySql("mysql:8.4")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.crt")), "/certs/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.key")), "/certs/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("ca.crt")), "/certs/ca.crt")
                .withCreateContainerCmdModifier { it.withEntrypoint("sh") }.withCommand("-c", script("mysqld", "mysql")).also { it.start() }
            maria = MariaDb("mariadb:11")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.crt")), "/certs/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("server.key")), "/certs/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(certs.file("ca.crt")), "/certs/ca.crt")
                .withCreateContainerCmdModifier { it.withEntrypoint("sh") }.withCommand("-c", script("mariadbd", "mysql")).also { it.start() }
        }

        @AfterClass @JvmStatic fun stop() { pg?.stop(); pgClientCert?.stop(); mysql?.stop(); maria?.stop() }
    }

    private fun ca(name: String = "ca.crt") = certs.file(name).toString()
    private fun pgDs(c: PostgreSQLContainer, host: String, ssl: SslSettings) =
        DataSource("t", "t", DbKind.POSTGRES, JdbcUrl.build(DbKind.POSTGRES, host, c.firstMappedPort, c.databaseName, true), c.username, ssl = ssl)
    private fun usesTls(ds: DataSource, password: String, sql: String) =
        openConnection(ds, password).use { c -> c.createStatement().executeQuery(sql).let { it.next(); it.getString(1) } }
    private val pgTls = "select ssl::text from pg_stat_ssl where pid = pg_backend_pid()"

    // --- PostgreSQL ----------------------------------------------------------------------------------

    @Test fun postgresEveryVerificationLevel() {
        val c = pg!!
        assertEquals("true", usesTls(pgDs(c, "localhost", SslSettings()), c.password, pgTls))                                           // require: encrypted, nothing checked
        assertEquals("true", usesTls(pgDs(c, "localhost", SslSettings(SslVerify.CA, ca())), c.password, pgTls))
        assertEquals("true", usesTls(pgDs(c, "localhost", SslSettings(SslVerify.FULL, ca())), c.password, pgTls))
        assertEquals("true", usesTls(pgDs(c, "127.0.0.1", SslSettings(SslVerify.CA, ca())), c.password, pgTls))                         // a CA check does not look at the name
        assertFailsWith<java.sql.SQLException>("el nombre no coincide") { usesTls(pgDs(c, "127.0.0.1", SslSettings(SslVerify.FULL, ca())), c.password, pgTls) }
        assertFailsWith<java.sql.SQLException>("otra CA") { usesTls(pgDs(c, "localhost", SslSettings(SslVerify.CA, ca("other.crt"))), c.password, pgTls) }
        assertFailsWith<java.sql.SQLException>("sin CA no se puede verificar") { usesTls(pgDs(c, "localhost", SslSettings(SslVerify.CA)), c.password, pgTls) }
    }

    @Test fun postgresClientCertificateIsPresentedInEitherKeyFormat() {
        val c = pgClientCert!!
        val noClient = SslSettings(SslVerify.CA, ca())
        assertFailsWith<java.sql.SQLException>("el servidor exige certificado de cliente") { usesTls(pgDs(c, "localhost", noClient), c.password, pgTls) }
        for (key in listOf("client.key", "client.rsa.key")) {
            val ssl = noClient.copy(clientCertFile = certs.file("client.crt").toString(), clientKeyFile = certs.file(key).toString())
            assertEquals("true", usesTls(pgDs(c, "localhost", ssl), c.password, pgTls), key)
        }
    }

    // --- MySQL and MariaDB ---------------------------------------------------------------------------

    private fun mysqlDs(kind: DbKind, host: String, port: Int, db: String, user: String, ssl: SslSettings) =
        DataSource("t", "t", kind, JdbcUrl.build(kind, host, port, db, true), user, ssl = ssl)
    private val cipher = "show session status like 'Ssl_cipher'"
    private fun cipherOf(ds: DataSource, password: String) = openConnection(ds, password).use { c -> c.createStatement().executeQuery(cipher).let { it.next(); it.getString(2) } }

    @Test fun mysqlEveryVerificationLevel() {
        val c = mysql!!
        fun ds(host: String, ssl: SslSettings) = mysqlDs(DbKind.MYSQL, host, c.firstMappedPort, c.databaseName, c.username, ssl)
        assertTrue(cipherOf(ds("localhost", SslSettings()), c.password).isNotBlank())
        assertTrue(cipherOf(ds("localhost", SslSettings(SslVerify.CA, ca())), c.password).isNotBlank())
        assertTrue(cipherOf(ds("localhost", SslSettings(SslVerify.FULL, ca())), c.password).isNotBlank())
        assertTrue(cipherOf(ds("127.0.0.1", SslSettings(SslVerify.CA, ca())), c.password).isNotBlank())
        assertFailsWith<java.sql.SQLException>("el nombre no coincide") { cipherOf(ds("127.0.0.1", SslSettings(SslVerify.FULL, ca())), c.password) }
        assertFailsWith<java.sql.SQLException>("otra CA") { cipherOf(ds("localhost", SslSettings(SslVerify.CA, ca("other.crt"))), c.password) }
    }

    @Test fun mariadbEveryVerificationLevel() {
        val c = maria!!
        fun ds(host: String, ssl: SslSettings) = mysqlDs(DbKind.MARIADB, host, c.firstMappedPort, c.databaseName, c.username, ssl)
        assertTrue(cipherOf(ds("localhost", SslSettings()), c.password).isNotBlank())
        assertTrue(cipherOf(ds("localhost", SslSettings(SslVerify.CA, ca())), c.password).isNotBlank())
        assertTrue(cipherOf(ds("localhost", SslSettings(SslVerify.FULL, ca())), c.password).isNotBlank())
        assertTrue(cipherOf(ds("127.0.0.1", SslSettings(SslVerify.CA, ca())), c.password).isNotBlank())
        assertFailsWith<java.sql.SQLException>("el nombre no coincide") { cipherOf(ds("127.0.0.1", SslSettings(SslVerify.FULL, ca())), c.password) }
        assertFailsWith<java.sql.SQLException>("otra CA") { cipherOf(ds("localhost", SslSettings(SslVerify.CA, ca("other.crt"))), c.password) }
    }

    @Test fun aBrokenCertificateFileIsReportedBeforeConnecting() {
        val c = pg!!
        val e = assertFailsWith<java.sql.SQLException> { usesTls(pgDs(c, "localhost", SslSettings(SslVerify.CA, ca(), clientCertFile = ca(), clientKeyFile = "/no/such/key.pem")), c.password, pgTls) }
        assertTrue("Certificados SSL" in e.message.orEmpty(), e.message)
    }
}
