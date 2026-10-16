package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.core.DataSource
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.core.Namespace
import ksqlc.drivers.ContentDiff
import ksqlc.drivers.Introspector
import ksqlc.drivers.SchemaSource
import ksqlc.drivers.Session
import ksqlc.drivers.diffTableContent

/**
 * Comparing the rows of table [leftObj] of the active connection with a table of any saved connection, by the left table's
 * primary key. The right side opens its own introspector and session, closed with the dialog (when it is the active
 * connection the open introspector is reused); the left rows are read through a session of the job's own.
 */
class ContentDiffJob(
    val leftName: String, val leftObj: DbObject, private val left: SchemaSource, private val activeId: String?, val sources: List<DataSource>,
    private val passwordOf: (DataSource) -> String?, private val scope: CoroutineScope,
) : AutoCloseable {
    var rightIndexSource by mutableStateOf(0)
        private set
    var rightNamespaces by mutableStateOf<List<Namespace>>(emptyList())
        private set
    var rightIndex by mutableStateOf(0)
        private set
    var rightTables by mutableStateOf<List<DbObject>>(emptyList())
        private set
    var rightTableIndex by mutableStateOf(0)
    var result by mutableStateOf<ContentDiff?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false
    private var right: SchemaSource? = null
    private var ownsRight = false
    private var leftSession: Session? = null
    private var rightSession: Session? = null

    /** Each call supersedes the earlier ones: a slow load that finishes late must not replace what was chosen after it. */
    private var generation = 0

    init { chooseSource(0) }

    /** Points the right side at [sources][index] and reads its schemas. */
    fun chooseSource(index: Int) {
        rightIndexSource = index
        result = null; error = null; rightNamespaces = emptyList(); rightTables = emptyList()
        val gen = synchronized(this) { ++generation }
        scope.launch {
            val ds = sources.getOrNull(index) ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val src: SchemaSource = if (ds.id == activeId) left else Introspector(ds, passwordOf(ds))
                    val owned = src !== left
                    val ns = try { src.namespaces() } catch (e: Exception) { if (owned) runCatching { (src as AutoCloseable).close() }; throw e }
                    val current = synchronized(this@ContentDiffJob) {
                        (gen == generation).also { if (it) { closeRight(); right = src; ownsRight = owned } }
                    }
                    if (!current && owned) runCatching { (src as AutoCloseable).close() }
                    ns.takeIf { current }
                }
            }.onSuccess { ns ->
                if (ns == null) return@onSuccess
                rightNamespaces = ns
                chooseNamespace(ns.indexOfFirst { it.label == leftObj.ns.label }.takeIf { it >= 0 } ?: 0)
            }.onFailure { if (gen == generation) error = "No se pudo abrir ${ds.name}: ${it.message}" }
        }
    }

    fun chooseNamespace(index: Int) {
        rightIndex = index; result = null; rightTables = emptyList()
        val ns = rightNamespaces.getOrNull(index) ?: return
        val src = right ?: return
        val gen = synchronized(this) { ++generation }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { src.objects(ns).filter { it.kind == DbObjectKind.TABLE } } }
                .onSuccess { tables ->
                    if (gen != generation) return@onSuccess
                    rightTables = tables
                    rightTableIndex = tables.indexOfFirst { it.name.equals(leftObj.name, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                }
                .onFailure { if (gen == generation) error = it.message }
        }
    }

    fun compare() {
        val r = rightTables.getOrNull(rightTableIndex); val rs = right
        if (r == null || rs == null || running) { error = error ?: "Elige la tabla de la derecha"; return }
        running = true; error = null; cancelled = false; result = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ls = leftSession ?: Session(sources[0], passwordOf(sources[0])).also { leftSession = it }
                    val rds = sources[rightIndexSource]
                    val rss = rightSession ?: Session(rds, passwordOf(rds)).also { rightSession = it }
                    diffTableContent(left, ls, leftObj, rs, rss, r, cancelled = { cancelled })
                }
            }.onSuccess { result = it; if (cancelled) error = "Comparación cancelada: el resultado es parcial" }
                .onFailure { error = it.message ?: it.toString() }
            running = false
        }
    }

    fun cancel() { cancelled = true }

    private fun closeRight() {
        if (ownsRight) runCatching { (right as? AutoCloseable)?.close() }
        right = null; ownsRight = false
        runCatching { rightSession?.close() }; rightSession = null
    }

    override fun close() { cancelled = true; closeRight(); runCatching { leftSession?.close() }; leftSession = null }
}
