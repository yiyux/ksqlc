package ksqlc.sql

data class TableRef(val schema: String?, val name: String, val isView: Boolean = false)
data class ColumnRef(val name: String, val type: String)

/** What the analysis needs to know about the database; implemented by the app's catalog, faked in tests. */
interface SchemaInfo {
    /** False when the table list was cut short, so an unknown name proves nothing. */
    val complete: Boolean
    fun schemas(): List<String>
    fun tables(): List<TableRef>
    /** Null while the columns of [table] have not been loaded. */
    fun columns(table: TableRef): List<ColumnRef>?
}

/** The table called [name] (optionally restricted to [schema]); case-insensitive. */
fun SchemaInfo.find(schema: String?, name: String): TableRef? =
    tables().firstOrNull { it.name.equals(name, true) && (schema == null || it.schema.equals(schema, true)) }

/** Identifier text without its quotes. */
fun unquote(s: String): String =
    if (s.length >= 2 && (s[0] == '"' || s[0] == '`') && s.last() == s[0]) s.substring(1, s.length - 1).replace("${s[0]}${s[0]}", s[0].toString()) else s
