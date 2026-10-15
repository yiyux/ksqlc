package ksqlc.storage

import ksqlc.core.DataSource
import ksqlc.core.DbKind
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.*

class ConnectionStoreTest {
    private val dir = Files.createTempDirectory("ksqlc-test")
    private val secrets = InMemorySecretStore()
    private val store = ConnectionStore(dir, secrets)
    private val pg = DataSource("1", "prod", DbKind.POSTGRES, "jdbc:postgresql://h/db", "me")

    @Test fun roundTripKeepsPasswordOutOfFile() {
        store.save(pg, "s3cret")
        assertEquals(listOf(pg), ConnectionStore(dir, secrets).list())
        assertEquals("s3cret", store.password("1"))
        assertFalse(dir.resolve("connections.json").readText().contains("s3cret"))
    }

    @Test fun manualTransactionsSurviveARestartAndOldFilesDefaultToAuto() {
        store.save(pg.copy(manualTx = true), "")
        assertTrue(ConnectionStore(dir, secrets).list().single().manualTx)
        dir.resolve("connections.json").toFile().writeText("""{"version":1,"connections":[{"id":"9","name":"viejo","kind":"SQLITE","url":"jdbc:sqlite:x"}]}""")
        assertFalse(ConnectionStore(dir, secrets).list().single().manualTx)
    }

    @Test fun sshSettingsAreSavedAndTheirSecretGoesToTheKeychain() {
        val tunnel = ksqlc.core.SshSettings(true, "jump.example", 2222, "me", ksqlc.core.SshAuth.KEY, "~/.ssh/id_ed25519")
        store.save(pg.copy(ssh = tunnel, sshSecret = "frase"), "dbpass")
        val back = ConnectionStore(dir, secrets).list().single()
        assertEquals(tunnel, back.ssh)
        assertNull(back.sshSecret)
        assertEquals("frase", store.sshSecret("1")); assertEquals("dbpass", store.password("1"))
        val file = dir.resolve("connections.json").readText()
        assertFalse(file.contains("frase")); assertFalse(file.contains("dbpass")); assertFalse(file.contains("sshSecret"))
        store.save(pg.copy(ssh = tunnel), "dbpass", sshSecret = "")      // blank clears it
        assertNull(store.sshSecret("1"))
        store.save(pg.copy(sshSecret = "otra"), "dbpass"); store.delete("1")
        assertNull(store.sshSecret("1")); assertNull(store.password("1"))
    }

    @Test fun oldFilesHaveNoTunnel() {
        dir.resolve("connections.json").toFile().writeText("""{"version":1,"connections":[{"id":"9","name":"viejo","kind":"POSTGRES","url":"jdbc:postgresql://h:5432/d"}]}""")
        assertFalse(ConnectionStore(dir, secrets).list().single().ssh.enabled)
    }

    @Test fun saveReplacesById() {
        store.save(pg, "a")
        store.save(pg.copy(name = "renamed"), "b")
        assertEquals(listOf("renamed"), store.list().map { it.name })
        assertEquals("b", store.password("1"))
    }

    @Test fun blankPasswordClearsSecret() {
        store.save(pg, "a")
        store.save(pg, "")
        assertNull(store.password("1"))
    }

    @Test fun deleteRemovesConnectionAndSecret() {
        store.save(pg, "a")
        store.delete("1")
        assertTrue(store.list().isEmpty())
        assertNull(store.password("1"))
    }

    @Test fun emptyWhenNoFile() = assertTrue(store.list().isEmpty())
}
