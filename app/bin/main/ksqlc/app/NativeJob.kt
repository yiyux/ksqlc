package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.core.DbKind
import ksqlc.drivers.*
import java.nio.file.Path

/**
 * A dump or a restore done by the engine's own command-line tool (pg_dump, mysqldump, sqlite3…), whose output goes to the
 * Output log. [scopes] are the things that can be dumped from where the dialog was opened (label to scope).
 */
class NativeJob(
    val restore: Boolean, val kind: DbKind, private val parts: JdbcUrl.Parts, private val user: String?, private val password: String?,
    val scopes: List<Pair<String, DumpScope>>, savedTools: Map<String, String>,
    private val scope: CoroutineScope, private val saveTool: (name: String, path: String) -> Unit, private val log: (String) -> Unit,
) {
    var scopeIndex by mutableStateOf(0)
    var schemaOnly by mutableStateOf(false)
    var dataOnly by mutableStateOf(false)
    var custom by mutableStateOf(false)
    var clean by mutableStateOf(false)
    var file by mutableStateOf("")
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var process: Process? = null
    @Volatile private var stopped = false

    private val saved = savedTools.toMutableMap()
    val toolNames: List<String> get() = if (restore) restoreToolNames(kind, custom) else dumpToolNames(kind)
    /** The tool path typed or remembered for the current tool; empty = look it up. */
    var toolPath by mutableStateOf(saved[toolNames.firstOrNull().orEmpty()].orEmpty())
    val found: Path? get() = findTool(toolNames, toolPath)

    /** Switching the custom format changes the restore tool, so its remembered path is looked up again. */
    fun onCustomChanged(value: Boolean) { custom = value; toolPath = saved[toolNames.firstOrNull().orEmpty()].orEmpty() }

    fun cancel() { stopped = true; process?.destroy() }

    fun start(onDone: (ok: Boolean) -> Unit) {
        if (running) return
        val tool = found ?: run { error = "No se encontró ${toolNames.joinToString(" ni ")}: indica su ruta"; return }
        if (file.isBlank()) { error = if (restore) "Elige el archivo que restaurar" else "Indica el archivo de destino"; return }
        val path = Path.of(file.trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))
        val spec = try {
            if (restore) restoreCommand(tool.toString(), kind, parts, user, password, path, custom)
            else dumpCommand(tool.toString(), kind, parts, user, password, scopes[scopeIndex].second, DumpOptions(schemaOnly, dataOnly, custom, clean), path)
        } catch (e: IllegalArgumentException) { error = e.message; return }
        toolNames.firstOrNull()?.let { name -> saved[name] = tool.toString(); saveTool(name, tool.toString()) }
        running = true; error = null; stopped = false
        val label = tool.fileName.toString()
        scope.launch {
            log("[$label] ${if (restore) "restaurando" else "volcando"} ${path.fileName}…")
            val code = withContext(Dispatchers.IO) {
                runCatching { runProcess(spec, { line -> scope.launch { log("[$label] $line") } }, { process = it }) }
            }
            running = false; process = null
            code.onSuccess { c ->
                val ok = c == 0 && !stopped
                log("[$label] " + if (stopped) "cancelado" else if (c == 0) "terminado" else "terminó con el código $c")
                if (ok) onDone(true) else error = if (stopped) "Cancelado" else "La herramienta terminó con el código $c (ver Output)"
            }.onFailure { error = it.message ?: it.toString(); log("[$label] error: $error") }
        }
    }
}
