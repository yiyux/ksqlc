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
