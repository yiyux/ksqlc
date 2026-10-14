package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.drivers.CellValue
import ksqlc.drivers.DmlStatement
import ksqlc.drivers.RowEdit
import ksqlc.drivers.buildDml
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.isBinaryType

/** A set of row numbers that Compose can observe (this Compose version has no `mutableStateSetOf`). */
class StateSet {
    private val m = mutableStateMapOf<Int, Unit>()
    operator fun contains(i: Int) = i in m
    fun add(i: Int): Boolean = m.put(i, Unit) == null
    fun remove(i: Int) { m.remove(i) }
    fun clear() = m.clear()
    val size: Int get() = m.size
    fun isNotEmpty() = m.isNotEmpty()
    fun toSet(): Set<Int> = m.keys.toSet()
    fun sorted(): List<Int> = m.keys.sorted()
}

/**
 * Changes waiting to be sent for one table tab: edited cells, rows marked for deletion and new rows. Row numbers
 * `0 until grid.rows.size` are the rows read; numbers after that are the new rows, in the order they were added.
 * Rows are matched to the database by [primaryKey]; without one (or on a view) existing rows cannot be edited.
 */
class TableEditor(val obj: DbObject, private val dialect: Dialect, private val kind: DbKind, val primaryKey: List<String>) {
    /** The engine this table lives in (it decides, for example, whether a DATE column has a time of day). */
    val engine: DbKind get() = kind

    val canInsert = obj.kind == DbObjectKind.TABLE
    val canEditRows = canInsert && primaryKey.isNotEmpty()

    /** Edited cells of existing rows: (row, column) → new value. */
    val edits = mutableStateMapOf<Pair<Int, Int>, CellValue>()
    val deleted = StateSet()
    /** New rows, one value per column. */
    val inserts = mutableStateListOf<SnapshotStateList<CellValue>>()

    /** Rows highlighted by clicking their number, and the row of the selected cell; `−` acts on them. */
    val selectedRows = StateSet()
    var cursorRow by mutableIntStateOf(-1)

    val hasChanges: Boolean get() = edits.isNotEmpty() || deleted.isNotEmpty() || inserts.isNotEmpty()
    val changeCount: Int get() = edits.keys.map { it.first }.toSet().size + deleted.size + inserts.size

    fun isInsertRow(row: Int, grid: ResultTab) = row >= grid.rows.size

    /** Why existing rows cannot be edited, for the status bar; null when they can. */
    fun readOnlyReason(): String? = when {
        obj.kind != DbObjectKind.TABLE -> "${obj.name} es una vista: solo lectura"
        primaryKey.isEmpty() -> "${obj.name} no tiene clave primaria: se pueden añadir filas, pero no editar ni borrar las existentes"
        else -> null
    }

    /** Sets a cell. Setting a cell back to what the database holds removes the pending edit. */
    fun setCell(row: Int, col: Int, v: CellValue, grid: ResultTab) {
        if (isInsertRow(row, grid)) { inserts.getOrNull(row - grid.rows.size)?.let { it[col] = v }; return }
        if (!canEditRows || row in deleted) return
        val original = grid.rows.getOrNull(row)?.getOrNull(col)
        val same = when (v) {
            is CellValue.Null -> original == null
            is CellValue.Text -> original != null && original.toString() == v.text
            is CellValue.Default -> true
        }
        if (same) edits.remove(row to col) else edits[row to col] = v
    }

    /** What the grid shows for a cell: the pending value if there is one, else what was read. */
    fun valueAt(row: Int, col: Int, grid: ResultTab): CellValue? {
        if (isInsertRow(row, grid)) return inserts.getOrNull(row - grid.rows.size)?.getOrNull(col)
        return edits[row to col]
    }

    fun isEditable(col: Int, grid: ResultTab) = !isBinaryType(grid.result.columns.getOrNull(col)?.typeName.orEmpty())

    fun addRow(columns: Int) {
        if (canInsert) inserts += mutableStateListOf<CellValue>().also { l -> repeat(columns) { l += CellValue.Default } }
    }

    /** Marks the selected rows for deletion; new rows that were selected are simply dropped. Returns how many rows were affected. */
    fun deleteSelected(grid: ResultTab): Int {
        val rows: Set<Int> = selectedRows.toSet().let { if (it.isEmpty() && cursorRow >= 0) setOf(cursorRow) else it }
        var n = 0
        for (r in rows.sortedDescending()) {
            if (isInsertRow(r, grid)) { if (inserts.removeAtOrNull(r - grid.rows.size)) n++ }
            else if (canEditRows && r in grid.rows.indices && deleted.add(r)) { edits.keys.filter { it.first == r }.forEach { edits.remove(it) }; n++ }
        }
        selectedRows.clear()
        return n
    }

    private fun SnapshotStateList<*>.removeAtOrNull(i: Int): Boolean = if (i in indices) { removeAt(i); true } else false

    fun revert() { edits.clear(); deleted.clear(); inserts.clear(); selectedRows.clear() }

    /** The statements that apply everything pending. Throws [ksqlc.drivers.DmlException] when a typed value does not fit its column. */
    fun statements(grid: ResultTab): List<DmlStatement> {
        val cols = grid.result.columns
        val updates = edits.entries.groupBy({ it.key.first }, { it.key.second to it.value }).filterKeys { it !in deleted && it in grid.rows.indices }
            .map { (row, cells) -> RowEdit(grid.rows[row], cells.associate { (c, v) -> cols[c].name to v }) }
        val news = inserts.map { row -> cols.indices.associate { cols[it].name to row[it] } }
        val dels = deleted.sorted().filter { it in grid.rows.indices }.map { grid.rows[it] }
        return buildDml(dialect, kind, obj, cols, primaryKey, updates, news, dels)
    }

    fun clear() = revert()
}
