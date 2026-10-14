package ksqlc.drivers

import ksqlc.core.StatementResult
import java.util.Base64

enum class ExportFormat(val label: String, val extension: String) {
    CSV("CSV", "csv"), TSV("TSV", "tsv"), JSON("JSON", "json"), SQL_INSERT("SQL INSERT", "sql"),
}

/**
 * Writes rows to [out] one at a time, so any size streams. Call [header] once, [row] per row, then [finish].
 * CSV/TSV quote a field only when it needs it (delimiter, quote or line break inside) and write NULL as an empty field.
 * JSON is an array of objects (numbers and booleans stay as such, everything else is a string; bytes are base64).
 * SQL INSERT writes one statement per row, with names quoted by [quote].
 */
class RowWriter(
    private val format: ExportFormat,
    private val columns: List<String>,
    private val out: Appendable,
    private val table: String = "export",
    private val quote: (String) -> String = { "\"" + it.replace("\"", "\"\"") + "\"" },
    private val withHeader: Boolean = true,
) {
    var count = 0L
        private set

    fun header() {
        when (format) {
            ExportFormat.CSV, ExportFormat.TSV -> if (withHeader) line(columns)
            ExportFormat.JSON -> out.append("[")
            ExportFormat.SQL_INSERT -> {}
        }
    }

    fun row(values: List<Any?>) {
        when (format) {
            ExportFormat.CSV, ExportFormat.TSV -> line(values.map(::text))
            ExportFormat.JSON -> {
                out.append(if (count == 0L) "\n  {" else ",\n  {")
                columns.forEachIndexed { i, c -> out.append(if (i == 0) "" else ", ").append(jsonString(c)).append(": ").append(jsonValue(values[i])) }
                out.append("}")
            }
            ExportFormat.SQL_INSERT ->
                out.append("insert into ").append(table).append(" (").append(columns.joinToString(", ", transform = quote)).append(") values (")
                    .append(values.joinToString(", ") { sqlLiteral(it) }).append(");\n")
        }
        count++
    }

    fun finish() {
        if (format == ExportFormat.JSON) out.append(if (count == 0L) "]\n" else "\n]\n")
    }

    private val delimiter get() = if (format == ExportFormat.TSV) '\t' else ','

    private fun line(fields: List<String?>) {
        fields.forEachIndexed { i, f ->
            if (i > 0) out.append(delimiter)
            if (f != null) out.append(if (f.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f)
        }
        out.append('\n')
    }

    private fun text(v: Any?): String? = when (v) {
        null -> null
        is ByteArray -> Base64.getEncoder().encodeToString(v)
        is java.math.BigDecimal -> v.toPlainString()
        else -> v.toString()
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is java.math.BigDecimal -> v.toPlainString()
        is Double -> if (v.isNaN() || v.isInfinite()) jsonString(v.toString()) else v.toString()
        is Float -> if (v.isNaN() || v.isInfinite()) jsonString(v.toString()) else v.toString()
        is Number -> v.toString()
        else -> jsonString(text(v)!!)
    }

    // shortcut: bytes as X'..' (MySQL, SQLite); PostgreSQL, SQL Server and Oracle spell binary literals differently, upgrade when exporting binary columns for them matters.
    private fun sqlLiteral(v: Any?): String = when (v) {
        is ByteArray -> "X'" + v.joinToString("") { "%02x".format(it) } + "'"
        else -> literal(v)
    }
}

internal fun jsonString(s: String): String = buildString {
    append('"')
    for (ch in s) when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch == '\n' -> append("\\n")
        ch == '\r' -> append("\\r")
        ch == '\t' -> append("\\t")
        ch < ' ' -> append("\\u%04x".format(ch.code))
        else -> append(ch)
    }
    append('"')
}

/**
 * Writes [already] (rows the caller has loaded) and then whatever is left in the cursor of [result], [batch] rows at a
 * time, stopping early once [cancelled] says so. Returns the rows written. [onProgress] gets the running count.
 */
fun exportRows(
    result: StatementResult.Rows, already: List<List<Any?>>, writer: RowWriter,
    cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}, batch: Int = 1000,
): Long {
    writer.header()
    for (r in already) { if (cancelled()) break; writer.row(r) }
    onProgress(writer.count)
    while (!cancelled() && !result.cursor.exhausted) {
        val rows = result.cursor.fetch(batch)
        if (rows.isEmpty()) break
        for (r in rows) { if (cancelled()) break; writer.row(r) }
        onProgress(writer.count)
    }
    writer.finish()
    return writer.count
}

/** Runs [sql] on [session] and exports everything it returns. Returns the rows written; throws with the database's message. */
fun exportQuery(
    session: Session, sql: String, newWriter: (List<String>) -> RowWriter,
    cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {},
): Long {
    when (val r = session.runScript(listOf(sql), keepRows = 0).single()) {
        is StatementResult.Rows -> try {
            return exportRows(r, emptyList(), newWriter(r.columns.map { it.name }), cancelled, onProgress)
        } finally { r.cursor.close() }
        is StatementResult.Failed -> error(r.message)
        else -> error("la consulta no devuelve filas")
    }
}
