package ksqlc.storage

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {
    private val dir = Files.createTempDirectory("ksqlc-hist")
    private val store = HistoryStore(dir)
    private fun e(n: Int, ok: Boolean = true) = HistoryEntry(n.toLong(), "select $n", 5, ok, n.toLong())

    @Test fun appendsAndLoadsNewestFirst() {
        store.append("a", listOf(e(1), e(2)))
        store.append("a", listOf(e(3, ok = false)))
        assertEquals(listOf(3L, 2L, 1L), store.load("a").map { it.time })
        assertEquals(false, store.load("a").first().ok)
        assertEquals(emptyList(), store.load("other"))
    }

    @Test fun keepsOnlyTheLastEntriesAndKeepsMultilineSql() {
        store.append("a", listOf(HistoryEntry(0, "select 1\nfrom \"t\"", 1, true)))
        assertEquals("select 1\nfrom \"t\"", store.load("a").single().sql)
        store.append("a", (1..HistoryStore.KEEP + HistoryStore.SLACK + 10).map { e(it) })
        val all = store.load("a")
        assertEquals(HistoryStore.KEEP, all.size)
        assertEquals((HistoryStore.KEEP + HistoryStore.SLACK + 10).toLong(), all.first().time)
    }

    @Test fun clearAndPrivateFile() {
        store.append("a/b", listOf(e(1)))
        val f = dir.resolve("history").toFile().listFiles()!!.single().toPath()
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)))
        store.clear("a/b")
        assertTrue(store.load("a/b").isEmpty())
    }
}
