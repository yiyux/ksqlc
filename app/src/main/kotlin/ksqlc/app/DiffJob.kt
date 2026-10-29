package ksqlc.app

import ksqlc.core.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.core.DataSource
import ksqlc.core.Namespace
import ksqlc.drivers.Introspector
import ksqlc.drivers.ObjectDiff
import ksqlc.drivers.SchemaSource
import ksqlc.drivers.diffSchemas

/**
 * Comparing the DDL of the objects in one schema of the active connection ([left]) with a schema of any saved connection.
 * The right side opens its own connection, closed with the dialog; when it is the active connection the open one is reused.
 */
class DiffJob(
    val leftName: String, private val left: SchemaSource, private val activeId: String?, val sources: List<DataSource>,
    private val passwordOf: (DataSource) -> String?, private val scope: CoroutineScope,
    initialLeft: Namespace?, val initialObject: String?,
) : AutoCloseable {
    var leftNamespaces by mutableStateOf<List<Namespace>>(emptyList())
        private set
    var leftIndex by mutableStateOf(0)
    var rightIndexSource by mutableStateOf(0)
        private set
    var rightNamespaces by mutableStateOf<List<Namespace>>(emptyList())
        private set
    var rightIndex by mutableStateOf(0)
    var results by mutableStateOf<List<ObjectDiff>?>(null)
        private set
    var selected by mutableStateOf<ObjectDiff?>(null)
    var hideSame by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false
    private var right: SchemaSource? = null
    private var ownsRight = false

    init {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { left.namespaces() } }
                .onSuccess { ns -> leftNamespaces = ns; leftIndex = ns.indexOf(initialLeft).coerceAtLeast(0) }
                .onFailure { error = it.message }
            chooseSource(0)
        }
    }

    /** Each call supersedes the earlier ones: a slow load that finishes late must not replace the source chosen after it. */
    private var generation = 0

    /** Points the right side at [sources][index] and reads its schemas. */
    fun chooseSource(index: Int) {
        rightIndexSource = index
        results = null; selected = null; error = null
        rightNamespaces = emptyList()   // nothing to compare against until the new side has loaded
        val gen = synchronized(this) { ++generation }
        scope.launch {
            val ds = sources.getOrNull(index) ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val src: SchemaSource = if (ds.id == activeId) left else Introspector(ds, passwordOf(ds))
                    val owned = src !== left
                    val ns = try { src.namespaces() } catch (e: Exception) { if (owned) runCatching { (src as AutoCloseable).close() }; throw e }
                    val current = synchronized(this@DiffJob) {
                        (gen == generation).also { if (it) { closeRight(); right = src; ownsRight = owned } }
                    }
                    if (!current && owned) runCatching { (src as AutoCloseable).close() }
                    ns.takeIf { current }
                }
            }.onSuccess { ns ->
                if (ns == null) return@onSuccess
                rightNamespaces = ns
                val want = leftNamespaces.getOrNull(leftIndex)?.label
                rightIndex = ns.indexOfFirst { it.label == want }.takeIf { it >= 0 } ?: 0
            }.onFailure { if (gen == generation) { rightNamespaces = emptyList(); error = tr("No se pudo abrir %s: %s", ds.name, it.message) } }
        }
    }

    fun compare() {
        val l = leftNamespaces.getOrNull(leftIndex); val r = rightNamespaces.getOrNull(rightIndex); val rs = right
        if (l == null || r == null || rs == null || running) { error = error ?: tr("Elige un esquema a cada lado"); return }
        running = true; error = null; cancelled = false; results = null; selected = null; progress = "0"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { diffSchemas(left, l, rs, r, { d, t -> progress = tr("%s/%s objetos", d, t) }, { cancelled }) } }
                .onSuccess { list ->
                    results = list
                    selected = list.firstOrNull { it.name.equals(initialObject, ignoreCase = true) } ?: list.firstOrNull { it.status != ksqlc.drivers.ObjectStatus.SAME }
                    if (cancelled) error = tr("Comparación cancelada: solo se muestran %d objetos", list.size)
                }
                .onFailure { error = it.message ?: it.toString() }
            running = false
        }
    }

    fun cancel() { cancelled = true }

    private fun closeRight() { if (ownsRight) runCatching { (right as? AutoCloseable)?.close() }; right = null; ownsRight = false }

    override fun close() { cancelled = true; closeRight() }
}
