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
import ksqlc.drivers.CopyColumns
import ksqlc.drivers.SchemaSource
import ksqlc.drivers.Session
import ksqlc.drivers.copyColumns
import ksqlc.drivers.copyInsertSql
import ksqlc.drivers.copyProblem
import ksqlc.drivers.copyRows
import ksqlc.drivers.syncTarget
import ksqlc.storage.HistoryEntry

/** What a copy will do, worked out against the target: [create] is null when the table is already there. */
class CopyPlan(val target: DbObject, val columns: CopyColumns, val create: String?, val insertSql: String, val identity: Set<String>, val problem: String?)

/**
 * Copying the rows of table [sourceObj] of the active connection into a table of any saved connection, chosen with [picker]. A target
 * name that is not in the chosen schema is created (same engine only) with the columns, types, not-null flags and primary key of the
 * source; one that is there gets the rows added, matched by column name. The copy runs on a session of its own that confirms by
 * itself, all or nothing. [afterCopy] runs when it ends well, to refresh whatever shows the target.
 */
class CopyTableJob(
    val leftName: String, val sourceObj: DbObject, private val left: SchemaSource, activeId: String?, val sources: List<DataSource>,
    private val passwordOf: (DataSource) -> String?, private val scope: CoroutineScope, private val afterCopy: (DataSource) -> Unit,
) : AutoCloseable {
    var targetName by mutableStateOf(sourceObj.name)
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false

    val picker = TablePicker(sources, left, activeId, sourceObj, passwordOf, scope, onReset = { message = null }, onClosing = {}, onError = { error = it })
    val targetSource: DataSource get() = sources[picker.sourceIndex]

    /** The table in the chosen schema that [targetName] names, if there is one. */
    val existing: DbObject? get() = picker.tables.firstOrNull { it.name.equals(targetName.trim(), ignoreCase = true) }

    /** What the copy would do and why it cannot, from what is known without reading the tables; null while nothing is chosen yet. */
    val hint: String? get() {
        if (picker.namespaces.isEmpty() || targetName.isBlank()) return null
        copyProblem(sources[0].kind, targetSource.kind, targetSource.options.readOnly, existing != null, null)?.let { return it }
        return if (existing != null) "Se añadirán las filas a ${existing!!.name}" else "Se creará la tabla ${targetName.trim()}"
    }
    val blocked: Boolean get() = hint == null || copyProblem(sources[0].kind, targetSource.kind, targetSource.options.readOnly, existing != null, null) != null

    /** Reads both tables to work out the copy. Blocking. */
    fun plan(): CopyPlan {
        val rs = picker.source ?: error("Elige la tabla de destino")
        val ns = picker.namespaces.getOrNull(picker.nsIndex) ?: error("Elige el esquema de destino")
        val found = existing
        val target = found ?: DbObject(ns, targetName.trim(), DbObjectKind.TABLE)
        val details = left.details(sourceObj)
        val kind = targetSource.kind
        val cols: CopyColumns; val identity: Set<String>
        if (found != null) {
            val t = syncTarget(rs, found)
            cols = copyColumns(details, rs.details(found), t.skip); identity = t.identity
        } else { cols = copyColumns(details, null); identity = emptySet() }
        val problem = copyProblem(sources[0].kind, kind, targetSource.options.readOnly, found != null, cols)
        val create = if (found == null && problem == null) rs.dialect.createTable(target, details) else null
        return CopyPlan(target, cols, create, copyInsertSql(rs.dialect, kind, target, cols.to, identity), identity, problem)
    }

    /** The statements of [plan] as text, for the preview. */
    fun sqlOf(p: CopyPlan) = listOfNotNull(p.create, p.insertSql.takeIf { p.problem == null }).joinToString("\n\n") { it.trimEnd(';') + ";" }

    fun copy(record: (String, List<HistoryEntry>) -> Unit) {
        if (running || blocked) return
        running = true; cancelled = false; progress = 0; error = null; message = null
        val ds = targetSource
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val p = plan()
                    p.problem?.let { throw IllegalStateException(it) }
                    val rs = picker.source!!
                    val rows = Session(sources[0], passwordOf(sources[0])).use { from ->
                        Session(ds, passwordOf(ds), autoCommit = true).use { to ->
                            copyRows(from, left.dialect, sourceObj, p.columns.from, to, rs.dialect, ds.kind, p.target, p.columns.to, p.identity, p.create, { cancelled }) { progress = it }.getOrThrow()
                        }
                    }
                    p to rows
                }
            }
            running = false
            val now = System.currentTimeMillis()
            outcome.onSuccess { (p, rows) ->
                record(ds.id, listOfNotNull(p.create, p.insertSql).map { HistoryEntry(now, it.trimEnd(';') + ";", 0, true) } +
                    HistoryEntry(now, "-- $rows filas copiadas desde $leftName.${sourceObj.name}", 0, true))
                picker.chooseNamespace(picker.nsIndex) // reads the tables again, so a table just created counts as existing; it also clears the message
                message = "Copiadas $rows filas a ${p.target.name} en ${ds.name}"
                afterCopy(ds)
            }.onFailure { error = if (cancelled) "Copia cancelada: no se aplicó nada" else "No se aplicó nada: ${it.message}" }
        }
    }

    fun cancel() { cancelled = true }

    override fun close() { cancelled = true; picker.close() }
}
