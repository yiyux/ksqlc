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
import ksqlc.drivers.ContentDiff
import ksqlc.drivers.DmlStatement
import ksqlc.drivers.RowStatus
import ksqlc.drivers.SchemaSource
import ksqlc.drivers.Session
import ksqlc.drivers.SyncTarget
import ksqlc.drivers.diffTableContent
import ksqlc.drivers.syncProblem
import ksqlc.drivers.syncStatements
import ksqlc.drivers.syncTarget
import ksqlc.storage.HistoryEntry

/**
 * Comparing the rows of table [leftObj] of the active connection with a table of any saved connection, by the left table's
 * primary key. The right side opens its own introspector and session, closed with the dialog (when it is the active
 * connection the open introspector is reused); the left rows are read through a session of the job's own.
 */
class ContentDiffJob(
    val leftName: String, val leftObj: DbObject, private val left: SchemaSource, private val activeId: String?, val sources: List<DataSource>,
    private val passwordOf: (DataSource) -> String?, private val scope: CoroutineScope,
) : AutoCloseable {
    var result by mutableStateOf<ContentDiff?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Which kinds of change the sync applies; deleting is off until asked for, because it is the one that loses rows. */
    var syncInsert by mutableStateOf(true)
    var syncUpdate by mutableStateOf(true)
    var syncDelete by mutableStateOf(false)
    /** The comparison was cancelled, so the result is not the whole of both tables. */
    var partial by mutableStateOf(false)
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncMessage by mutableStateOf<String?>(null)
        private set
    @Volatile private var cancelled = false
    /** What the result was compared against: the table a sync changes, and what it does not take in an insert or update. */
    private var comparedRight: DbObject? = null
    private var comparedTarget: SyncTarget? = null
    private var leftSession: Session? = null
    private var rightSession: Session? = null

    private val picker = TablePicker(
        sources, left, activeId, leftObj, passwordOf, scope,
        onReset = { result = null }, onClosing = { runCatching { rightSession?.close() }; rightSession = null }, onError = { error = it },
    )
    val rightIndexSource: Int get() = picker.sourceIndex
    val rightNamespaces get() = picker.namespaces
    val rightIndex: Int get() = picker.nsIndex
    val rightTables get() = picker.tables
    var rightTableIndex: Int
        get() = picker.tableIndex
        set(v) { picker.tableIndex = v }
    private val right: SchemaSource? get() = picker.source

    init { chooseSource(0) }

    /** Points the right side at [sources][index] and reads its schemas. */
    fun chooseSource(index: Int) { error = null; picker.chooseSource(index) }

    fun chooseNamespace(index: Int) = picker.chooseNamespace(index)

    val rightSource: DataSource get() = sources[rightIndexSource]
    val rightObj: DbObject? get() = comparedRight

    /** Why the right table cannot be synced with the result shown, or null when it can. */
    val syncReason: String? get() = result?.let { syncProblem(it, partial, rightSource.options.readOnly) }

    /** How many rows each kind of change would touch. */
    fun count(status: RowStatus) = result?.rows?.count { it.status == status } ?: 0

    /** The statements for the kinds that are checked. */
    fun syncStatements(): List<DmlStatement> {
        val d = result ?: return emptyList(); val o = comparedRight ?: return emptyList(); val rs = right ?: return emptyList()
        val t = comparedTarget ?: SyncTarget(emptySet(), emptySet())
        return syncStatements(rs.dialect, rightSource.kind, o, d, syncInsert, syncUpdate, syncDelete, t.skip, t.identity)
    }

    /**
     * Runs the sync on a session of its own that confirms by itself, whatever the connection's transaction mode: all or nothing.
     * [record] gets the statements for the history of the right connection; the tables are compared again afterwards.
     */
    fun applySync(record: (String, List<HistoryEntry>) -> Unit) {
        val statements = syncStatements()
        if (syncing || statements.isEmpty() || syncReason != null) return
        val ds = rightSource
        syncing = true; error = null; syncMessage = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Session(ds, passwordOf(ds), autoCommit = true).use { it.execute(statements) } }
            val now = System.currentTimeMillis()
            // shortcut: a long sync is recorded as its first statements and a count, so the history does not fill up with thousands of lines
            val shown = statements.take(HISTORY_MAX).map { HistoryEntry(now, it.preview.trimEnd(';') + ";", 0, outcome.isSuccess) } +
                listOfNotNull(statements.size.takeIf { it > HISTORY_MAX }?.let { HistoryEntry(now, "-- y ${it - HISTORY_MAX} sentencias más de la sincronización", 0, outcome.isSuccess) })
            record(ds.id, shown)
            syncing = false
            outcome.onSuccess { compare(); syncMessage = "Sincronizado: ${statements.size} sentencias aplicadas en ${ds.name}" }
                .onFailure { error = "No se aplicó nada: ${it.message}" }
        }
    }

    fun compare() {
        val r = rightTables.getOrNull(rightTableIndex); val rs = right
        if (r == null || rs == null || running) { error = error ?: "Elige la tabla de la derecha"; return }
        running = true; error = null; syncMessage = null; cancelled = false; result = null; partial = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ls = leftSession ?: Session(sources[0], passwordOf(sources[0])).also { leftSession = it }
                    val rds = sources[rightIndexSource]
                    val rss = rightSession ?: Session(rds, passwordOf(rds)).also { rightSession = it }
                    diffTableContent(left, ls, leftObj, rs, rss, r, cancelled = { cancelled }) to syncTarget(rs, r)
                }
            }.onSuccess { (diff, target) ->
                comparedRight = r; comparedTarget = target; partial = cancelled; result = diff
                if (cancelled) error = "Comparación cancelada: el resultado es parcial"
            }
                .onFailure { error = it.message ?: it.toString() }
            running = false
        }
    }

    fun cancel() { cancelled = true }

    private companion object { const val HISTORY_MAX = 100 }

    override fun close() { cancelled = true; picker.close(); runCatching { leftSession?.close() }; leftSession = null }
}
