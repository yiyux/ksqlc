package ksqlc.core

/** An editable copy of a table, what the Modify dialog edits. [origin] of each element is its name in the database, null when it is new. */
data class TableModel(
    val name: String,
    val comment: String? = null,
    val columns: List<ColumnModel> = emptyList(),
    val keys: List<KeyModel> = emptyList(),
    val foreignKeys: List<ForeignKeyModel> = emptyList(),
    val indexes: List<IndexModel> = emptyList(),
    val checks: List<CheckModel> = emptyList(),
    /** Names of keys and indexes the engine has but this model cannot hold, so the dialog neither shows nor touches them. */
    val skipped: List<String> = emptyList(),
)

/** Sequence options of an identity column; a null option is the server's default. */
data class Identity(
    val always: Boolean = false,
    val min: String? = null, val max: String? = null, val step: String? = null, val cache: String? = null,
    val cycle: Boolean = false,
)

/** [type] and [default] are the text the server gives; [generated] is the expression of a generated column, [generatedStored] whether it is stored (else virtual). */
data class ColumnModel(
    val origin: String?, val name: String, val type: String,
    val notNull: Boolean = false, val default: String? = null, val comment: String? = null,
    val identity: Identity? = null, val generated: String? = null, val generatedStored: Boolean = true,
)

data class KeyModel(
    val origin: String?, val name: String, val primary: Boolean, val columns: List<String>,
    val deferrable: Boolean = false, val initiallyDeferred: Boolean = false,
)

/** [onDelete] and [onUpdate] are `NO ACTION`, `RESTRICT`, `CASCADE`, `SET NULL` or `SET DEFAULT`. */
data class ForeignKeyModel(
    val origin: String?, val name: String, val columns: List<String>,
    val refNs: Namespace, val refTable: String, val refColumns: List<String>,
    val onDelete: String = "NO ACTION", val onUpdate: String = "NO ACTION",
    val deferrable: Boolean = false, val initiallyDeferred: Boolean = false,
)

/** [name] is a column name, or an SQL expression when [expression]; [order] is "", "ASC" or "DESC". */
data class IndexColumn(
    val name: String, val expression: Boolean = false,
    val order: String = "", val collation: String? = null, val opClass: String? = null,
)

data class IndexModel(
    val origin: String?, val name: String, val unique: Boolean, val method: String = "btree",
    val columns: List<IndexColumn>, val include: List<String> = emptyList(), val condition: String? = null,
)

data class CheckModel(val origin: String?, val name: String, val expression: String)
