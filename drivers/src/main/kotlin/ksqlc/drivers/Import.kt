package ksqlc.drivers

import ksqlc.core.tr

import ksqlc.core.ColumnInfo
import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.drivers.dialect.Dialect

/** A value in the file that does not fit its column; [row] counts the file's records from 1, header included. */
class ImportException(val row: Int, val column: String, message: String) : RuntimeException(tr("fila %s, columna %s: %s", row, column, message))

/**
 * Inserts the records of [rows] into [table]. [mapping] gives, for each file column, the index in [columns] it goes to
 * (null = ignore it). [firstRow] is the file record number of the first row of [rows], for error messages.
 * Values are converted with [parseParam] as they stream, so a bad one stops the run and, by [Session.executeBatch],
 * leaves the table as it was. Returns the number of rows inserted, or the failure.
 */
fun importRows(
    session: Session, dialect: Dialect, kind: DbKind, table: DbObject, columns: List<ColumnInfo>,
    mapping: List<Int?>, rows: Iterator<List<String?>>, firstRow: Int = 1, onProgress: (Long) -> Unit = {},
): Result<Long> {
    val used = mapping.withIndex().filter { it.value != null }
    if (used.isEmpty()) return Result.failure(IllegalArgumentException(tr("ninguna columna del archivo va a una columna de la tabla")))
    used.groupBy { it.value }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        return Result.failure(IllegalArgumentException(tr("la columna %s está asignada más de una vez", columns[it!!].name)))
    }
    val sql = "insert into ${dialect.qualified(table)} (${used.joinToString(", ") { dialect.quote(columns[it.value!!].name) }}) values (${used.joinToString(", ") { "?" }})"
    var n = firstRow - 1
    val params = object : Iterator<List<Any?>> {
        override fun hasNext() = rows.hasNext()
        override fun next(): List<Any?> {
            val rec = rows.next(); n++
            return used.map { (i, target) ->
                val col = columns[target!!]
                val v = rec.getOrNull(i) ?: return@map null
                try { parseParam(v, col.typeName, kind) } catch (e: IllegalArgumentException) { throw ImportException(n, col.name, e.message ?: tr("valor no válido")) }
            }
        }
    }
    return session.executeBatch(sql, params, onProgress = onProgress)
}
