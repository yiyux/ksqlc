package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.drivers.ExportFormat
import ksqlc.drivers.RowWriter
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

/** Native file picker; null when cancelled. Blocks until the user answers, so call it from a click handler. */
fun chooseFile(save: Boolean, title: String, suggested: String? = null): String? {
    val d = FileDialog(null as java.awt.Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (suggested != null) d.file = suggested
    d.isVisible = true
    val name = d.file ?: return null
    return Path.of(d.directory ?: "", name).toString()
}

/**
 * What can be exported: [write] feeds a writer (built by the job once the columns are known) and returns the rows written.
 * [table] and [quote] name the target of SQL INSERT output.
 */
class ExportRequest(
    val name: String, val table: String, val quote: (String) -> String, val note: String?,
    val write: (newWriter: (List<String>) -> RowWriter, cancelled: () -> Boolean, progress: (Long) -> Unit) -> Long,
)

/** Rows the clipboard export stops at; a text that big is already more than a paste target copes with. */
const val CLIPBOARD_ROWS = 10_000

/** One export dialog: the choices on screen and the run itself. [report] gets the outcome line (status bar and log). */
class ExportJob(val request: ExportRequest, private val scope: CoroutineScope, private val report: (String) -> Unit) {
    var format by mutableStateOf(ExportFormat.CSV)
    var toClipboard by mutableStateOf(false)
    var path by mutableStateOf("")
    var header by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Runs the export off the UI thread; [setClipboard] is called on the UI thread with the text, [onDone] after a run that did not fail. */
    fun start(setClipboard: (String) -> Unit, onDone: () -> Unit) {
        if (running) return
        if (!toClipboard && path.isBlank()) { error = "Indica el archivo de destino"; return }
        running = true; error = null; progress = 0; cancelled = false
        scope.launch {
            var writer: RowWriter? = null
            val stop = { cancelled || (toClipboard && (writer?.count ?: 0) >= CLIPBOARD_ROWS) }
            fun newWriter(out: Appendable) = { cols: List<String> -> RowWriter(format, cols, out, request.table, request.quote, header).also { writer = it } }
            val file = if (toClipboard) null else Path.of(path.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            val text = StringBuilder()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (file == null) request.write(newWriter(text), stop) { progress = it }
                    else Files.newBufferedWriter(file).use { out -> request.write(newWriter(out), stop) { progress = it } }
                }.onFailure { if (file != null) runCatching { Files.deleteIfExists(file) } }
            }
            running = false
            outcome.onSuccess { rows ->
                val where = if (file == null) "el portapapeles" else file.toString()
                if (file == null) setClipboard(text.toString())
                val limited = file == null && rows >= CLIPBOARD_ROWS
                report("$rows filas exportadas a $where" + if (cancelled) " (cancelado)" else if (limited) " (límite de $CLIPBOARD_ROWS filas)" else "")
                onDone()
            }.onFailure { error = it.message ?: it.toString(); report("Error al exportar: $error") }
        }
    }
}
