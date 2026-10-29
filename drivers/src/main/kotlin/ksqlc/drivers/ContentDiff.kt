package ksqlc.drivers

import ksqlc.core.tr

import ksqlc.core.DbObject
import ksqlc.core.StatementResult
import java.math.BigDecimal
import java.nio.ByteBuffer

enum class RowStatus { ONLY_LEFT, ONLY_RIGHT, DIFFERENT }

/** One row that is not the same on both sides. [changed] are the indexes (into [ContentDiff.columns]) of the cells that differ. */
data class RowDiff(val key: List<Any?>, val status: RowStatus, val left: List<Any?>?, val right: List<Any?>?, val changed: Set<Int>)

/**
 * The rows of two tables compared by primary key over the columns they share.
 * [truncated]: a side had more rows than the limit, so "only on one side" may just be a row that was not read.
 */
data class ContentDiff(
    val columns: List<String>, val key: List<String>, val rows: List<RowDiff>, val same: Int, val truncated: Boolean,
    val onlyLeftColumns: List<String>, val onlyRightColumns: List<String>,
    /** [columns] as the right table spells them, and the right table's own primary key: what a script that changes the right table needs. */
    val rightColumns: List<String> = columns, val rightKey: List<String> = key,
)

/** A value in a form that is equal across drivers: blobs by content, numbers by value (`1` = `1.0`), anything else by its text. */
private fun canonical(v: Any?): Any? = when (v) {
    null -> null
    is ByteArray -> ByteBuffer.wrap(v)
    is Double -> if (v.isFinite()) BigDecimal(v.toString()).stripTrailingZeros() else v.toString()
    is Float -> if (v.isFinite()) BigDecimal(v.toString()).stripTrailingZeros() else v.toString()
    is BigDecimal -> v.stripTrailingZeros()
    is Number -> BigDecimal(v.toString()).stripTrailingZeros()
    else -> v.toString()
}

/**
 * Compares [left] and [right] rows, both laid out as [columns], matching them by the [key] columns. The right side is held in
 * memory. Rows only on the left come first in their own order, then those only on the right.
 * Stops early when [cancelled] returns true; the list so far is returned.
 */
fun diffRows(
    columns: List<String>, key: List<String>, left: Iterable<List<Any?>>, right: Iterable<List<Any?>>,
    truncated: Boolean = false, onlyLeftColumns: List<String> = emptyList(), onlyRightColumns: List<String> = emptyList(),
    cancelled: () -> Boolean = { false },
): ContentDiff {
    val keyIdx = key.map { k -> columns.indexOfFirst { it.equals(k, ignoreCase = true) }.also { require(it >= 0) { tr("Falta la columna de la clave %s", k) } } }
    fun keyOf(row: List<Any?>) = keyIdx.map { canonical(row[it]) }
    val byKey = LinkedHashMap<List<Any?>, List<Any?>>()
    for (row in right) {
        if (cancelled()) break
        check(byKey.put(keyOf(row), row) == null) { tr("La clave se repite en la tabla de la derecha: %s", keyIdx.map { row[it] }) }
    }
    val out = ArrayList<RowDiff>()
    var same = 0
    for (l in left) {
        if (cancelled()) break
        val k = keyOf(l)
        val r = byKey.remove(k)
        if (r == null) { out += RowDiff(keyIdx.map { l[it] }, RowStatus.ONLY_LEFT, l, null, emptySet()); continue }
        val changed = columns.indices.filter { canonical(l[it]) != canonical(r[it]) }.toSet()
        if (changed.isEmpty()) same++ else out += RowDiff(keyIdx.map { l[it] }, RowStatus.DIFFERENT, l, r, changed)
    }
    if (!cancelled()) byKey.values.forEach { r -> out += RowDiff(keyIdx.map { r[it] }, RowStatus.ONLY_RIGHT, null, r, emptySet()) }
    return ContentDiff(columns, key, out, same, truncated, onlyLeftColumns, onlyRightColumns)
}

/** Rows of [cols] of [o] through [session]: up to [limit], and whether there were more. */
private fun readRows(session: Session, src: SchemaSource, o: DbObject, cols: List<String>, limit: Int, cancelled: () -> Boolean): Pair<List<List<Any?>>, Boolean> {
    val sql = "select ${cols.joinToString(", ") { src.dialect.quote(it) }} from ${src.dialect.qualified(o)}"
    when (val r = session.runScript(listOf(sql), keepRows = 0).single()) {
        is StatementResult.Failed -> error("${o.name}: ${r.message}")
        is StatementResult.Updated -> error(tr("%s: la consulta no devolvió filas", o.name))
        is StatementResult.Rows -> r.cursor.use { c ->
            val rows = ArrayList<List<Any?>>()
            // one row past the limit tells "exactly limit rows" from "more"
            while (rows.size <= limit && !c.exhausted && !cancelled()) rows.addAll(c.fetch(minOf(1000, limit + 1 - rows.size)))
            return rows.take(limit) to (rows.size > limit)
        }
    }
}

/**
 * Compares the rows of table [leftObj] with those of [rightObj] by the primary key of the left table, over the columns both have
 * (matched by name, ignoring case). [leftSession] / [rightSession] read the rows; at most [limit] rows are read from each.
 * Fails if the left table has no primary key or a key column is missing on the right.
 */
fun diffTableContent(
    left: SchemaSource, leftSession: Session, leftObj: DbObject, right: SchemaSource, rightSession: Session, rightObj: DbObject,
    limit: Int = 200_000, cancelled: () -> Boolean = { false },
): ContentDiff {
    val ld = left.details(leftObj); val rd = right.details(rightObj)
    require(ld.primaryKey.isNotEmpty()) { tr("La tabla %s no tiene clave primaria", leftObj.name) }
    val rightNames = rd.columns.map { it.name }
    val common = ld.columns.map { it.name }.filter { n -> rightNames.any { it.equals(n, ignoreCase = true) } }
    ld.primaryKey.firstOrNull { k -> common.none { it.equals(k, ignoreCase = true) } }
        ?.let { throw IllegalArgumentException(tr("La columna de la clave %s no existe en %s", it, rightObj.name)) }
    // shortcut: both sides held in memory up to `limit` rows, a merge over sorted reads would need the same collation on both engines
    val (lRows, lMore) = readRows(leftSession, left, leftObj, common, limit, cancelled)
    val rightCols = common.map { c -> rightNames.first { it.equals(c, ignoreCase = true) } }
    val (rRows, rMore) = readRows(rightSession, right, rightObj, rightCols, limit, cancelled)
    return diffRows(
        common, ld.primaryKey, lRows, rRows, lMore || rMore,
        ld.columns.map { it.name }.filter { it !in common },
        rightNames.filter { n -> common.none { it.equals(n, ignoreCase = true) } },
        cancelled,
    ).copy(rightColumns = rightCols, rightKey = rd.primaryKey)
}
