package ksqlc.core

/** A browsable container: a schema (PostgreSQL, SQLite) or a catalog/database (MySQL, MariaDB). */
data class Namespace(val catalog: String?, val schema: String?) {
    val label: String get() = schema ?: catalog ?: ""
}

enum class DbObjectKind { TABLE, VIEW, MATERIALIZED_VIEW, SEQUENCE, ROUTINE }

data class DbObject(val ns: Namespace, val name: String, val kind: DbObjectKind, val comment: String? = null) {
    /** Tables and views can be read with `select *`. */
    val isReadable: Boolean get() = kind == DbObjectKind.TABLE || kind == DbObjectKind.VIEW || kind == DbObjectKind.MATERIALIZED_VIEW
}

data class ColumnInfo(
    val name: String,
    val typeName: String,
    val size: Int? = null,
    val scale: Int? = null,
    val nullable: Boolean = true,
    val default: String? = null,
    val autoIncrement: Boolean = false,
)

data class IndexInfo(val name: String, val unique: Boolean, val columns: List<String>)

data class ForeignKeyInfo(
    val name: String?,
    val columns: List<String>,
    val refNs: Namespace,
    val refTable: String,
    val refColumns: List<String>,
)

data class TableDetails(
    val columns: List<ColumnInfo>,
    val primaryKeyName: String? = null,
    val primaryKey: List<String> = emptyList(),
    val indexes: List<IndexInfo> = emptyList(),
    val foreignKeys: List<ForeignKeyInfo> = emptyList(),
)
