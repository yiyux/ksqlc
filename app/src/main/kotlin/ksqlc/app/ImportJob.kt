package ksqlc.app

import ksqlc.core.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.core.ColumnInfo
import ksqlc.core.DataSource
import ksqlc.core.DbObject
import ksqlc.drivers.CsvReader
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.importRows
import java.nio.file.Files
import java.nio.file.Path

/** Field separators the dialog offers, in the order of its segmented control. */
val DELIMITERS = listOf("," to tr("Coma"), ";" to tr("Punto y coma"), "\t" to tr("Tabulador"))

private const val PREVIEW_ROWS = 6

/**
 * Loading a CSV file into [obj] (a table): the file choices, a preview, which file column goes to which table column,
 * and the run. The import is one transaction on its own connection and is confirmed when it ends.
 */
class ImportJob(
    val obj: DbObject, val columns: List<ColumnInfo>, private val target: DataSource, private val password: String?,
    private val scope: CoroutineScope, private val report: (String) -> Unit,
) {
    var path by mutableStateOf("")
    var delimiter by mutableStateOf(0)
    var header by mutableStateOf(true)
    /** The first records of the file, header included; empty until a readable file is chosen. */
    var preview by mutableStateOf<List<List<String?>>>(emptyList())
        private set
    /** For each file column, the index in [columns] it goes to, or null to ignore it. */
    val mapping = mutableStateListOf<Int?>()
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val fileColumnCount: Int get() = preview.maxOfOrNull { it.size } ?: 0

    /** Name of file column [i]: its header, or "Columna n+1". */
    fun fileColumnName(i: Int) = (if (header) preview.firstOrNull()?.getOrNull(i) else null) ?: tr("Columna %d", i + 1)

    private fun resolve() = Path.of(path.trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))

    /** Reads the start of the file and proposes a mapping (by name when there is a header, else by position). Blocking. */
    fun loadPreview() {
        val file = runCatching { resolve() }.getOrNull()?.takeIf { path.isNotBlank() && Files.isReadable(it) && !Files.isDirectory(it) }
        val rows = if (file == null) emptyList() else runCatching {
            Files.newBufferedReader(file).use { r -> CsvReader(r, DELIMITERS[delimiter].first[0]).asSequence().take(PREVIEW_ROWS).toList() }
        }.getOrDefault(emptyList())
        preview = rows
        mapping.clear()
        repeat(fileColumnCount) { i ->
            val byName = if (header) columns.indexOfFirst { it.name.equals(fileColumnName(i), ignoreCase = true) }.takeIf { it >= 0 } else null
            mapping += byName ?: if (!header && i < columns.size) i else null
        }
    }

    fun start(onDone: () -> Unit) {
        if (running) return
        if (preview.isEmpty()) { error = tr("Elige un archivo CSV que se pueda leer"); return }
        running = true; error = null; progress = 0
        val map = mapping.toList(); val skip = header; val sep = DELIMITERS[delimiter].first[0]; val file = resolve()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    Session(target, password, autoCommit = true).use { s ->
                        Files.newBufferedReader(file).use { r ->
                            val reader = CsvReader(r, sep)
                            if (skip) reader.next()
                            importRows(s, Dialect.of(target.kind), target.kind, obj, columns, map, reader.asSequence().iterator(), if (skip) 2 else 1) { progress = it }.getOrThrow()
                        }
                    }
                }
            }
            running = false
            outcome.onSuccess { n -> report(tr("%s filas importadas en %s", n, obj.name)); onDone() }
                .onFailure { error = it.message ?: it.toString(); report(tr("Error al importar: %s", error)) }
        }
    }
}
