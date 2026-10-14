package ksqlc.drivers

import ksqlc.core.Column
import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.drivers.dialect.Dialect
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** What the user put in a cell: typed text, SQL NULL, or "let the database pick" (only meaningful in a new row). */
sealed interface CellValue {
    data class Text(val text: String) : CellValue
    data object Null : CellValue
    data object Default : CellValue
}

/** A value PostgreSQL must receive untyped (uuid, json, enums…): bound with `Types.OTHER`. */
data class UntypedParam(val text: String)

/** One statement with bound parameters; [preview] is the same SQL with the values written in, for display only. */
data class DmlStatement(val sql: String, val params: List<Any?>, val preview: String)

/** Changes to one existing row: its values as read ([originalRow], in column order) and the new value of each changed column. */
class RowEdit(val originalRow: List<Any?>, val changes: Map<String, CellValue>)

/** A typed cell value that could not be converted for [column]. */
class DmlException(val column: String, message: String) : IllegalArgumentException(message)

private val INTEGER_TYPES = setOf("INT", "INTEGER", "INT2", "INT4", "INT8", "SMALLINT", "TINYINT", "MEDIUMINT", "BIGINT", "SERIAL", "SMALLSERIAL", "BIGSERIAL", "YEAR")
private val DECIMAL_TYPES = setOf("NUMERIC", "DECIMAL", "NUMBER", "DEC", "MONEY", "SMALLMONEY")
private val FLOAT_TYPES = setOf("FLOAT", "FLOAT4", "FLOAT8", "DOUBLE", "DOUBLE PRECISION", "REAL", "BINARY_FLOAT", "BINARY_DOUBLE")
private val BOOLEAN_TYPES = setOf("BOOL", "BOOLEAN")
private val BINARY_WORDS = listOf("BLOB", "BYTEA", "BINARY", "VARBINARY", "IMAGE", "RAW", "BFILE")
private val TEXT_WORDS = listOf("CHAR", "TEXT", "CLOB", "STRING", "NAME", "CITEXT", "LONG", "ROWID", "INTERVAL")

/** The base type name: upper-case, without sizes, `unsigned` or `[]`. */
private fun baseType(typeName: String) = typeName.uppercase().replace(Regex("""\(.*?\)"""), "").replace("UNSIGNED", "").replace("IDENTITY", "").replace("[]", "").trim()

/** True for column types the grid cannot edit as text (binary data). */
fun isBinaryType(typeName: String) = baseType(typeName).let { t -> !t.startsWith("BINARY_") && BINARY_WORDS.any { it in t } }   // Oracle's BINARY_FLOAT/BINARY_DOUBLE are numbers

/**
 * Converts what was typed into the Java value to bind for a column of [typeName]. Empty text is NULL for every
 * non-text type. Dates are always bound as typed values: as text, SQL Server would read them in the login's language (5 January as 1 May). SQLite keeps dates as the text typed, since it has no date type. Throws [IllegalArgumentException]
 * (with a message fit for the user) when the text does not fit the type.
 */
fun parseParam(text: String, typeName: String, kind: DbKind): Any? {
    val t = baseType(typeName)
    val isText = TEXT_WORDS.any { it in t }
    if (isBinaryType(typeName)) throw IllegalArgumentException("las columnas binarias no se pueden editar")
    if (isText) return text
    if (text.isBlank()) return null
    val s = text.trim()
    fun bad(what: String): Nothing = throw IllegalArgumentException("«$s» no es $what")
    return when {
        kind == DbKind.SQLITE && t in INTEGER_TYPES -> s.toLongOrNull() ?: bad("un entero")
        t in INTEGER_TYPES -> s.toBigIntegerOrNull()?.let { if (it.bitLength() < 64) it.toLong() else it } ?: bad("un entero")
        kind == DbKind.SQLITE && (t in DECIMAL_TYPES || t in FLOAT_TYPES || t == "NUMERIC") -> s.toLongOrNull() ?: s.toDoubleOrNull() ?: bad("un número")
        t in DECIMAL_TYPES -> runCatching { BigDecimal(s) }.getOrNull() ?: bad("un número")
        t in FLOAT_TYPES -> s.toDoubleOrNull() ?: bad("un número")
        t in BOOLEAN_TYPES || (t == "BIT" && kind != DbKind.POSTGRES) -> when (s.lowercase()) {
            "true", "t", "1", "yes", "y", "si", "sí" -> true
            "false", "f", "0", "no", "n" -> false
            else -> bad("un booleano (true/false)")
        }
        kind == DbKind.SQLITE && (t == "DATE" || t.startsWith("TIME") || t == "DATETIME") -> s
        kind == DbKind.ORACLE && t == "DATE" -> // an Oracle DATE carries a time of day
            runCatching { Timestamp.valueOf(LocalDateTime.parse(s.replace(' ', 'T'))) }.getOrNull()
                ?: runCatching { Timestamp.valueOf(LocalDate.parse(s).atStartOfDay()) }.getOrNull() ?: bad("una fecha (aaaa-mm-dd hh:mm:ss)")
        t == "DATE" -> runCatching { Date.valueOf(LocalDate.parse(s)) }.getOrNull() ?: bad("una fecha (aaaa-mm-dd)")
        t == "TIME" || t == "TIME WITHOUT TIME ZONE" -> runCatching { Time.valueOf(LocalTime.parse(s).withNano(0)) }.getOrNull() ?: bad("una hora (hh:mm:ss)")
        t == "TIMESTAMP" || t == "DATETIME" || t == "DATETIME2" || t == "SMALLDATETIME" || t == "TIMESTAMP WITHOUT TIME ZONE" || t == "TIMESTAMPLTZ" || t == "TIMESTAMP WITH LOCAL TIME ZONE" ->
            runCatching { Timestamp.valueOf(LocalDateTime.parse(s.replace(' ', 'T'))) }.getOrNull()
                ?: runCatching { Timestamp.valueOf(LocalDate.parse(s).atStartOfDay()) }.getOrNull() ?: bad("una fecha y hora (aaaa-mm-dd hh:mm:ss)")
        t == "TIMESTAMPTZ" || t == "TIMESTAMP WITH TIME ZONE" || t == "DATETIMEOFFSET" ->
            runCatching { OffsetDateTime.parse(s.replace(' ', 'T')) }.getOrNull() ?: bad("una fecha y hora con zona (2026-01-31 10:00:00+01:00)")
        kind == DbKind.POSTGRES -> UntypedParam(text)
        else -> text
    }
}

internal fun literal(v: Any?): String = when (v) {
    null -> "NULL"
    is Boolean -> if (v) "TRUE" else "FALSE"
    is Number -> v.toString()
    is UntypedParam -> "'" + v.text.replace("'", "''") + "'"
    else -> "'" + v.toString().replace("'", "''") + "'"
}

private fun render(sql: String, params: List<Any?>): String {
    var i = 0
    return sql.map { if (it == '?') literal(params[i++]) else it.toString() }.joinToString("")
}

private fun stmt(sql: String, params: List<Any?>) = DmlStatement(sql, params, render(sql, params))

/**
 * The statements that apply the pending changes to [obj]: deletes first (so a row can be replaced by one with the same key),
 * then updates, then inserts. Existing rows are found by [primaryKey] using the values they had when read. [columns] is the
 * grid's column order, which [RowEdit.originalRow] and [deletes] follow. Throws [DmlException] for a value that does not fit its column.
 */
fun buildDml(
    dialect: Dialect, kind: DbKind, obj: DbObject, columns: List<Column>, primaryKey: List<String>,
    updates: List<RowEdit>, inserts: List<Map<String, CellValue>>, deletes: List<List<Any?>>,
): List<DmlStatement> {
    val table = dialect.qualified(obj)
    val typeOf = columns.associate { it.name.lowercase() to it.typeName }
    val keyIndexes = primaryKey.map { k -> columns.indexOfFirst { it.name.equals(k, true) }.also { check(it >= 0) { "la clave primaria $k no está entre las columnas" } } }
    check(keyIndexes.isNotEmpty() || (updates.isEmpty() && deletes.isEmpty())) { "la tabla no tiene clave primaria" }

    fun where(row: List<Any?>) = keyIndexes.joinToString(" and ") { dialect.quote(columns[it].name) + " = ?" } to keyIndexes.map { row[it] }
    fun bind(column: String, v: CellValue): Any? = when (v) {
        is CellValue.Null -> null
        is CellValue.Default -> null
        is CellValue.Text -> try { parseParam(v.text, typeOf[column.lowercase()] ?: "", kind) } catch (e: IllegalArgumentException) { throw DmlException(column, e.message ?: "valor no válido") }
    }

    val out = ArrayList<DmlStatement>()
    for (row in deletes) { val (w, p) = where(row); out += stmt("delete from $table where $w", p) }
    for (u in updates) {
        val changed = u.changes.filterValues { it !is CellValue.Default }
        if (changed.isEmpty()) continue
        val set = changed.keys.joinToString(", ") { dialect.quote(it) + " = ?" }
        val (w, wp) = where(u.originalRow)
        out += stmt("update $table set $set where $w", changed.map { (c, v) -> bind(c, v) } + wp)
    }
    for (row in inserts) {
        val given = row.filterValues { it !is CellValue.Default }
        out += if (given.isEmpty()) dialect.emptyInsert(table, dialect.quote(columns.first().name)).let { DmlStatement(it, emptyList(), it) }
        else stmt("insert into $table (${given.keys.joinToString(", ") { dialect.quote(it) }}) values (${given.keys.joinToString(", ") { "?" }})", given.map { (c, v) -> bind(c, v) })
    }
    return out
}
