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
import ksqlc.drivers.renderExport
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

/** The first rows of what will be exported, used to draw the preview. */
class ExportSample(val columns: List<String>, val rows: List<List<Any?>>)

const val PREVIEW_ROWS_EXPORT = 20

/**
 * What can be exported: [source] is shown to the user, [sample] reads the rows the preview needs (without consuming
 * anything), and [write] feeds a writer (built by the job once the columns are known) and returns the rows written.
 * [table] and [quote] name the target of SQL INSERT output.
 */
class ExportRequest(
    val name: String, val source: String, val table: String, val quote: (String) -> String, val note: String?,
    val sample: () -> ExportSample,
    /** [writeLoaded], when set, writes only the [loaded] rows the grid already has; [write] then means every row of the query. */
    val loaded: Int = 0, val writeLoaded: WriteFn? = null,
    val write: WriteFn,
)

typealias WriteFn = (newWriter: (List<String>) -> RowWriter, cancelled: () -> Boolean, progress: (Long) -> Unit) -> Long

/** Rows the clipboard export stops at; a text that big is already more than a paste target copes with. */
const val CLIPBOARD_ROWS = 10_000

/** One export dialog: the choices on screen, the preview and the run itself. [report] gets the outcome line (status bar and log). */
class ExportJob(val request: ExportRequest, private val scope: CoroutineScope, private val report: (String) -> Unit) {
    var format by mutableStateOf(ExportFormat.CSV)
        private set
    var path by mutableStateOf("")
        private set
    var header by mutableStateOf(false)
    var transpose by mutableStateOf(false)
    var rowHeader by mutableStateOf(false)
    /** Every row of the query (true) or only those the grid has loaded; only offered when the request has [ExportRequest.writeLoaded]. */
    var allRows by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sample by mutableStateOf<ExportSample?>(null)
        private set
    var sampleError by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false
    private var pathTouched = false

    /** Transpose and the two headers only mean something for the delimited formats. */
    val delimited: Boolean get() = format == ExportFormat.CSV || format == ExportFormat.TSV

    init {
        path = defaultPath(format)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { request.sample() } }
                .onSuccess { sample = it }.onFailure { sampleError = it.message ?: it.toString() }
        }
    }

    private fun defaultPath(f: ExportFormat) = Path.of(System.getProperty("user.home"), request.name.replace(Regex("[^\\p{L}\\p{N}_.-]"), "_") + "." + f.extension).toString()

    /** Changes the format; the file name follows it until the user has typed their own. */
    fun chooseFormat(f: ExportFormat) { format = f; if (!pathTouched) path = defaultPath(f) }

    fun editPath(p: String) { path = p; pathTouched = true }

    /** What the file would start with, for the right-hand pane. */
    fun previewText(): String {
        val s = sample ?: return ""
        return renderExport(format, s.columns, s.rows, request.table, request.quote, header, transpose && delimited, rowHeader && delimited)
    }

    fun cancel() { cancelled = true }

    /** Runs the export off the UI thread; [setClipboard] is called on the UI thread with the text, [onDone] after a run that did not fail. */
    fun start(toClipboard: Boolean, setClipboard: (String) -> Unit, onDone: () -> Unit) {
        if (running) return
        if (!toClipboard && path.isBlank()) { error = "Indica el archivo de destino"; return }
        running = true; error = null; progress = 0; cancelled = false
        scope.launch {
            var writer: RowWriter? = null
            val stop = { cancelled || (toClipboard && (writer?.count ?: 0) >= CLIPBOARD_ROWS) }
            fun newWriter(out: Appendable) = { cols: List<String> ->
                RowWriter(format, cols, out, request.table, request.quote, header, transpose && delimited, rowHeader && delimited).also { writer = it }
            }
            val file = if (toClipboard) null else Path.of(path.trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))
            val text = StringBuilder()
            val write = if (allRows) request.write else request.writeLoaded ?: request.write
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (file == null) write(newWriter(text), stop) { progress = it }
                    else Files.newBufferedWriter(file).use { out -> write(newWriter(out), stop) { progress = it } }
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
