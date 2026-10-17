package ksqlc.drivers

import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.drivers.dialect.Dialect
import java.time.temporal.Temporal

/** Most rows a sync changes in one go. */
const val SYNC_LIMIT = 10_000

/**
 * Why the right table cannot be made equal to the left one from [diff], or null when it can. [partial]: the comparison was
 * cancelled. A diff that did not read every row would turn rows that were never read into inserts and deletes.
 */
fun syncProblem(diff: ContentDiff, partial: Boolean, readOnly: Boolean, limit: Int = SYNC_LIMIT): String? = when {
    diff.rows.isEmpty() -> "No hay diferencias que sincronizar"
    readOnly -> "La conexión de la derecha es de solo lectura"
    partial -> "La comparación se canceló: faltan filas por leer y no se puede sincronizar"
    diff.truncated -> "Alguna tabla tiene más filas de las que se leen: lo que parece faltar a un lado puede ser una fila sin leer"
    diff.rightKey.map { it.lowercase() }.toSet() != diff.key.map { it.lowercase() }.toSet() ->
        "La clave primaria de la derecha (${diff.rightKey.joinToString().ifEmpty { "ninguna" }}) no es la de la izquierda (${diff.key.joinToString()}): un cambio podría tocar varias filas"
    diff.rows.size > limit -> "Hay ${diff.rows.size} filas distintas y se sincronizan $limit como mucho de una vez"
    else -> null
}

/** What the target table does not take in an insert or update: [skip] are generated columns and [identity] identity ones, both lower-case. */
class SyncTarget(val skip: Set<String>, val identity: Set<String>)

/** Reads from [src] which columns of [o] are generated (where the engine says so: MySQL, PostgreSQL, SQL Server) and which are identity. */
fun syncTarget(src: SchemaSource, o: DbObject): SyncTarget = SyncTarget(
    runCatching { src.tableModel(o)?.columns?.filter { it.generated != null }?.map { it.name.lowercase() }?.toSet() }.getOrNull().orEmpty(),
    runCatching { src.details(o).columns.filter { it.autoIncrement }.map { it.name.lowercase() }.toSet() }.getOrDefault(emptySet()),
)

/**
 * The value as the target can take it. PostgreSQL gets text and anything it has no plain type for (uuid, json, enums…) untyped, so the
 * column decides what it is. shortcut: across different engines some types may not fit; the server refuses and nothing is applied.
 */
private fun bindable(v: Any?, kind: DbKind): Any? = when {
    v == null -> null
    kind == DbKind.POSTGRES && v is String -> UntypedParam(v)
    v is Number || v is String || v is Boolean || v is ByteArray || v is java.sql.Date || v is java.sql.Time || v is java.sql.Timestamp || v is Temporal -> v
    kind == DbKind.POSTGRES -> UntypedParam(v.toString())
    else -> v.toString()
}

/**
 * The statements that make the right table (as [target]) equal to the left one: delete the rows only on the right, change the cells that
 * differ, insert the rows only on the left, in that order. Rows are found by the key values they have on the right.
 * [skip] columns (generated) are left out of inserts and updates; on SQL Server, inserts into a table with an [identity] column among
 * the compared ones are wrapped in `set identity_insert`.
 */
fun syncStatements(
    dialect: Dialect, kind: DbKind, target: DbObject, diff: ContentDiff,
    insert: Boolean, update: Boolean, delete: Boolean, skip: Set<String> = emptySet(), identity: Set<String> = emptySet(),
): List<DmlStatement> {
    val table = dialect.qualified(target)
    val cols = diff.rightColumns.map { dialect.quote(it) }
    val keyIdx = diff.key.map { k -> diff.columns.indexOfFirst { it.equals(k, ignoreCase = true) } }
    val where = keyIdx.joinToString(" and ") { "${cols[it]} = ?" }
    fun usable(i: Int) = diff.columns[i].lowercase() !in skip
    val out = ArrayList<DmlStatement>()

    if (delete) for (d in diff.rows.filter { it.status == RowStatus.ONLY_RIGHT }) {
        out += stmt("delete from $table where $where", keyIdx.map { bindable(d.right!![it], kind) })
    }
    if (update) for (d in diff.rows.filter { it.status == RowStatus.DIFFERENT }) {
        val set = d.changed.filter { it !in keyIdx && usable(it) }.sorted()
        if (set.isEmpty()) continue
        out += stmt(
            "update $table set ${set.joinToString(", ") { "${cols[it]} = ?" }} where $where",
            set.map { bindable(d.left!![it], kind) } + keyIdx.map { bindable(d.right!![it], kind) },
        )
    }
    val inserts = diff.rows.filter { it.status == RowStatus.ONLY_LEFT }
    if (insert && inserts.isNotEmpty()) {
        val use = diff.columns.indices.filter { usable(it) }
        if (use.isNotEmpty()) {
            val sql = "insert into $table (${use.joinToString(", ") { cols[it] }}) values (${use.joinToString(", ") { "?" }})"
            fun plain(s: String) = DmlStatement(s, emptyList(), s)
            val identityInsert = kind == DbKind.MSSQL && use.any { diff.columns[it].lowercase() in identity }
            if (identityInsert) out += plain("set identity_insert $table on")
            inserts.forEach { d -> out += stmt(sql, use.map { bindable(d.left!![it], kind) }) }
            if (identityInsert) out += plain("set identity_insert $table off")
        }
    }
    return out
}
