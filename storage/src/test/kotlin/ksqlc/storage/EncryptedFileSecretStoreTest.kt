package ksqlc.storage

import ksqlc.core.DataSource
import ksqlc.core.DbKind
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.*

class EncryptedFileSecretStoreTest {
    private val dir = Files.createTempDirectory("ksqlc-enc")
    private val file = dir.resolve("secrets.enc")

    @Test fun roundTripSurvivesReopenAndHidesThePassword() {
        EncryptedFileSecretStore(file, "master".toCharArray()).apply { set("1", "s3cret-pw"); set("2", "x"); delete("2") }
        assertFalse(file.readText().contains("s3cret-pw"))
        val again = EncryptedFileSecretStore(file, "master".toCharArray())
        assertEquals("s3cret-pw", again.get("1")); assertNull(again.get("2"))
    }

    @Test fun wrongMasterPasswordIsRejected() {
        EncryptedFileSecretStore(file, "master".toCharArray()).set("1", "a")
        assertFailsWith<IllegalArgumentException> { EncryptedFileSecretStore(file, "otra".toCharArray()) }
    }

    @Test fun switchSecretsMovesDbAndSshSecrets() {
        val from = InMemorySecretStore(); val to = InMemorySecretStore()
        val store = ConnectionStore(dir, from)
        store.save(DataSource("1", "a", DbKind.POSTGRES, "jdbc:postgresql://h/db"), "pw", sshSecret = "frase")
        store.switchSecrets(to)
        assertEquals("pw", to.get("1")); assertEquals("frase", to.get("1#ssh"))
        assertNull(from.get("1")); assertNull(from.get("1#ssh"))
        assertEquals("pw", store.password("1"))
    }
}
