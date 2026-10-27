package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.StatementResult

/** One result grid. Rows accumulate page by page; [loadMore] blocks, so call it off the UI thread. */
class ResultTab(val title: String, val result: StatementResult.Rows) {
    val rows = mutableStateListOf<List<Any?>>()
    /** An execution plan: shown as aligned text rather than a grid. */
    var plan = false
    var exhausted by mutableStateOf(false)
    var loading by mutableStateOf(false)

    /** Local filters: column index -> the values (as text, null = SQL NULL) its rows may have. Only the loaded rows are filtered. */
    val filters = mutableStateMapOf<Int, Set<String?>>()

    /** The column the rows are ordered by and whether ascending; null = as read. */
    var sort by mutableStateOf<Pair<Int, Boolean>?>(null)

    fun passes(row: List<Any?>) = filters.all { (j, allowed) -> row.getOrNull(j)?.toString() in allowed }

    /** The distinct values of column [j] among the loaded rows with how many rows have each, in natural order (NULL last). */
    fun distinct(j: Int): List<Pair<String?, Int>> =
        rows.groupingBy { it.getOrNull(j)?.toString() }.eachCount().toList().sortedWith { a, b ->
            val x = a.first; val y = b.first
            when {
                x == null || y == null -> if (x == null && y == null) 0 else if (x == null) 1 else -1
                else -> { val nx = x.toBigDecimalOrNull(); val ny = y.toBigDecimalOrNull(); if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y, ignoreCase = true) }
            }
        }

    fun loadMore(pageSize: Int) {
        loading = true
        try {
            rows.addAll(result.cursor.fetch(pageSize))
        } finally {
            // also after a failed read: the cursor is closed then, and there is nothing more to ask it for
            exhausted = result.cursor.exhausted
            loading = false
        }
    }

    fun close() = result.cursor.close()

    /**
     * Set when this result is a plain read of one table and its key: the rows can be edited, sent back through [edit]'s own
     * connection and re-read there. [edit]'s grid replaces this one once something has been sent.
     */
    var edit by mutableStateOf<DataTab?>(null)

    /** The grid to show: the first read, or the latest one after changes were sent. */
    val shown: ResultTab get() = edit?.grid ?: this

    /** Closes this result and the connection that edits it. */
    fun dispose() { close(); edit?.close() }
}

/** The columns of [rows] as aligned monospace text; a single column (most plans) is just its lines, with no header. */
fun planText(columns: List<String>, rows: List<List<Any?>>): String {
    fun cell(v: Any?) = (v?.toString() ?: "NULL").replace("\r\n", " ").replace('\n', ' ')
    if (columns.size == 1) return rows.joinToString("\n") { it[0]?.toString() ?: "NULL" }
    val table = listOf(columns) + rows.map { r -> r.map(::cell) }
    val widths = columns.indices.map { j -> table.maxOf { it[j].length } }
    return table.joinToString("\n") { r -> r.mapIndexed { j, c -> c.padEnd(widths[j]) }.joinToString("  ").trimEnd() }
}

/**
 * A table opened from the explorer: its grid, the private session that streams it, and the WHERE / ORDER BY the
 * user typed. [reload] re-runs `baseSql` with them; it blocks, so call it off the UI thread.
 */
class DataTab(val obj: ksqlc.core.DbObject, first: ResultTab, val session: ksqlc.drivers.Session, private val baseSql: String, val editor: TableEditor) {
    var grid by mutableStateOf(first)
        private set
    var where by mutableStateOf("")
    var orderBy by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    /** The order requested with the header arrows; the next [reload] gives it to the grid it builds (a plain reload clears it). */
    var pendingSort: Pair<Int, Boolean>? = null
    /** Manual transactions: this tab's connection holds changes that are not confirmed yet. */
    var pending by mutableStateOf(false)
        private set

    /** Anything the user would lose by closing the tab. */
    val unsaved: Boolean get() = editor.hasChanges || pending

    /** Sends the pending edits and reloads. Blocking. Returns an error message, or null on success (then [affected] holds the row count). */
    fun submit(): String? {
        val statements = try { editor.statements(grid) } catch (e: ksqlc.drivers.DmlException) { return "columna ${e.column}: ${e.message}" }
        if (statements.isEmpty()) { editor.clear(); return null }
        loading = true
        try {
            val result = session.execute(statements)
            pending = session.pending
            result.exceptionOrNull()?.let { return it.message ?: it.toString() }
            affected = result.getOrDefault(0)
            editor.clear()
        } finally { loading = false }
        return reload()
    }

    var affected = 0L
        private set

    fun commit() { session.commit(); pending = false }
    fun rollback() { session.rollback(); pending = false; editor.revert() }
    fun syncPending() { pending = session.pending }

    fun query() = buildString {
        append(baseSql)
        if (where.isNotBlank()) append(" where ").append(where.trim())
        if (orderBy.isNotBlank()) append(" order by ").append(orderBy.trim())
    }

    /** Runs [query]; on success the grid is replaced, on failure the old grid stays and the error message is returned. */
    fun reload(): String? {
        loading = true
        try {
            when (val r = session.runScript(listOf(query()), PAGE).single()) {
                is StatementResult.Rows -> {
                    val next = ResultTab(grid.title, r).also { it.loadMore(PAGE); it.sort = pendingSort; pendingSort = null }
                    val old = grid
                    grid = next
                    runCatching { old.close() }
                    return null
                }
                is StatementResult.Failed -> { pendingSort = null; return r.message }
                else -> { pendingSort = null; return "sin resultado" }
            }
        } finally { loading = false }
    }

    fun close() { runCatching { grid.close() }; runCatching { session.close() } }
}
