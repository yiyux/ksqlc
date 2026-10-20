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
import ksqlc.drivers.Introspector
import ksqlc.drivers.SchemaSource

/**
 * Choosing a table of any saved connection: the data source, then its schema, then the table, starting from the ones named like
 * [leftObj]. The source it opens is its own unless it is the active connection ([left] is reused); it is closed when another is
 * chosen and by [close]. [onReset] is called when the choice changes (what depended on it is stale), [onClosing] just before
 * the opened source is let go, and [onError] gets what failed.
 */
class TablePicker(
    val sources: List<DataSource>, private val left: SchemaSource, private val activeId: String?, private val leftObj: DbObject,
    private val passwordOf: (DataSource) -> String?, private val scope: CoroutineScope,
    private val onReset: () -> Unit, private val onClosing: () -> Unit, private val onError: (String) -> Unit,
) : AutoCloseable {
    var sourceIndex by mutableStateOf(0)
        private set
    var namespaces by mutableStateOf<List<Namespace>>(emptyList())
        private set
    var nsIndex by mutableStateOf(0)
        private set
    var tables by mutableStateOf<List<DbObject>>(emptyList())
        private set
    var tableIndex by mutableStateOf(0)

    /** The opened source of the chosen data source, once its schemas are read. */
    var source: SchemaSource? = null
        private set
    private var owns = false

    /** Each call supersedes the earlier ones: a slow load that finishes late must not replace what was chosen after it. */
    private var generation = 0

    /** Points at [sources][index] and reads its schemas. */
    fun chooseSource(index: Int) {
        sourceIndex = index
        onReset(); namespaces = emptyList(); tables = emptyList()
        val gen = synchronized(this) { ++generation }
        scope.launch {
            val ds = sources.getOrNull(index) ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val src: SchemaSource = if (ds.id == activeId) left else Introspector(ds, passwordOf(ds))
                    val owned = src !== left
                    val ns = try { src.namespaces() } catch (e: Exception) { if (owned) runCatching { (src as AutoCloseable).close() }; throw e }
                    val current = synchronized(this@TablePicker) {
                        (gen == generation).also { if (it) { release(); source = src; owns = owned } }
                    }
                    if (!current && owned) runCatching { (src as AutoCloseable).close() }
                    ns.takeIf { current }
                }
            }.onSuccess { ns ->
                if (ns == null) return@onSuccess
                namespaces = ns
                chooseNamespace(ns.indexOfFirst { it.label == leftObj.ns.label }.takeIf { it >= 0 } ?: 0)
            }.onFailure { if (gen == generation) onError("No se pudo abrir ${ds.name}: ${it.message}") }
        }
    }

    fun chooseNamespace(index: Int) {
        nsIndex = index; onReset(); tables = emptyList()
        val ns = namespaces.getOrNull(index) ?: return
        val src = source ?: return
        val gen = synchronized(this) { ++generation }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { src.objects(ns).filter { it.kind == DbObjectKind.TABLE } } }
                .onSuccess { found ->
                    if (gen != generation) return@onSuccess
                    tables = found
                    tableIndex = found.indexOfFirst { it.name.equals(leftObj.name, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                }
                .onFailure { if (gen == generation) onError(it.message ?: it.toString()) }
        }
    }

    private fun release() {
        onClosing()
        if (owns) runCatching { (source as? AutoCloseable)?.close() }
        source = null; owns = false
    }

    override fun close() = release()
}
