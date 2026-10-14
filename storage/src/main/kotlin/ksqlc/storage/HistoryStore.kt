package ksqlc.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** One statement that ran. [time] is epoch milliseconds; [rows] is the row or affected count when there was one. */
@Serializable
data class HistoryEntry(val time: Long, val sql: String, val millis: Long, val ok: Boolean, val rows: Long? = null)

/**
 * Statements run per data source, one JSON object per line in [dir]/history/<id>.jsonl (private to the user:
 * the SQL can carry data). Keeps the last [KEEP] entries; the file is only rewritten once it has grown [SLACK] past that.
 */
class HistoryStore(dir: Path) {
    private val dir = dir.resolve("history")
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(id: String) = dir.resolve(id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jsonl")

    @Synchronized
    fun append(id: String, entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        val f = file(id)
        dir.createDirectories()
        if (!f.exists()) {
            Files.createFile(f)
            runCatching { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")) }
        }
        Files.writeString(f, entries.joinToString("") { json.encodeToString(it) + "\n" }, StandardOpenOption.APPEND)
        val lines = Files.readAllLines(f)
        if (lines.size > KEEP + SLACK) atomicWrite(f, lines.takeLast(KEEP).joinToString("") { it + "\n" })
    }

    /** Newest first. Lines that cannot be read are skipped. */
    @Synchronized
    fun load(id: String): List<HistoryEntry> {
        val f = file(id)
        if (!f.exists()) return emptyList()
        return Files.readAllLines(f).mapNotNull { l -> runCatching { json.decodeFromString<HistoryEntry>(l) }.getOrNull() }.takeLast(KEEP).asReversed()
    }

    @Synchronized
    fun clear(id: String) { Files.deleteIfExists(file(id)) }

    companion object {
        const val KEEP = 2000
        const val SLACK = 500
        fun default() = HistoryStore(Path.of(System.getProperty("user.home"), ".ksqlc"))
    }
}
