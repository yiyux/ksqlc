package ksqlc.drivers.dialect

import ksqlc.core.*

internal fun mysqlAlterTable(d: Dialect, o: DbObject, before: TableModel, after: TableModel): List<String> {
    val q = d.qualified(o)
    fun id(n: String) = d.quote(n)
    fun ids(l: List<String>) = l.joinToString(", ") { d.quote(it) }
    // backslash is an escape in a MySQL string unless NO_BACKSLASH_ESCAPES is on
    fun lit(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"
    fun alter(vararg clauses: String) = "alter table $q " + clauses.joinToString(", ")
    val out = ArrayList<String>()

    /** The whole definition of a column, which is what `add column` and `modify column` both take. [auto] leaves `auto_increment` for later when its key is not there yet. */
    fun definition(c: ColumnModel, auto: Boolean): String = buildString {
        append(id(c.name), " ", c.type)
        val gen = c.generated
        if (gen != null) append(" generated always as ($gen) ", if (c.generatedStored) "stored" else "virtual")
        append(if (c.notNull) " not null" else " null")
        if (gen == null) {
            c.default?.let { append(" default ", it) }
            if (auto && c.identity != null) append(" auto_increment")
        }
        c.comment?.let { append(" comment ", lit(it)) }
    }

    val moves = ColumnMoves(before, after)
    val kept = moves.kept
    fun mapped(l: List<String>) = moves.mapped(l)
    // the server updates keys, indexes and foreign keys on a rename but a check keeps its text, so a check that uses a renamed column is written again
    fun renamedIn(e: String) = moves.renamed.entries.fold(e) { s, (old, new) -> s.replace("`$old`", "`$new`") }
    fun touched(e: String) = renamedIn(e) != e || moves.mentionsGone(e)

    val keys = changes(before.keys, after.keys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    // MySQL can rename neither a foreign key nor a check, so a name-only change is a drop and a create
    val fkChanges = changes(before.foreignKeys, after.foreignKeys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val fkDropped = fkChanges.dropped + fkChanges.renamed.map { it.first }
    val fkCreated = fkChanges.created + fkChanges.renamed.map { it.second }
    val checks = changes(before.checks, after.checks, { it.origin }, { it.name }) { y, x -> y.expression == x.expression && !touched(y.expression) }
    val checksDropped = checks.dropped + checks.renamed.map { it.first }
    val checksCreated = (checks.created + checks.renamed.map { it.second }).map { x ->
        val y = before.checks.find { it.name == x.origin }
        if (y != null && y.expression == x.expression) x.copy(expression = renamedIn(x.expression)) else x
    }
    val indexes = changes(before.indexes, after.indexes, { it.origin }, { it.name }) { y, x ->
        val columns = y.columns.map { if (it.expression) it else it.copy(name = mapped(listOf(it.name)).single()) }
        y.columns.none { it.expression && moves.mentionsGone(it.name) } &&
            y.copy(origin = null, name = "", columns = columns) == x.copy(origin = null, name = "")
    }

    // changing the primary key's columns is one statement: the table may have an auto_increment column that needs it at every moment
    val pkChange = keys.dropped.any { it.primary } && keys.created.any { it.primary }

    // 1. what goes away
    fkDropped.forEach { out += alter("drop foreign key ${id(it.name)}") }
    checksDropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    keys.dropped.filter { !pkChange || !it.primary }.forEach { out += alter(if (it.primary) "drop primary key" else "drop index ${id(it.name)}") }
    indexes.dropped.forEach { out += alter("drop index ${id(it.name)}") }

    // 2. columns; `auto_increment` waits until its key exists
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }.forEach { out += alter("drop column ${id(it.name)}") }
    for ((a, b) in kept) if (a.name != b.name) out += alter("rename column ${id(b.name)} to ${id(a.name)}")
    for (c in after.columns.filter { it.origin == null }) out += alter("add column ${definition(c, auto = false)}")
    for ((a, b) in kept) {
        val lostAuto = a.identity == null && b.identity != null
        if (a.generated == null && (a.type != b.type || a.notNull != b.notNull || a.default != b.default || a.comment != b.comment || lostAuto))
            out += alter("modify column ${definition(a, auto = a.identity != null && b.identity != null)}")
    }

    // 3. what is new or only renamed
    keys.renamed.filter { !it.second.primary }.forEach { (y, x) -> out += alter("rename index ${id(y.name)} to ${id(x.name)}") }
    indexes.renamed.forEach { (y, x) -> out += alter("rename index ${id(y.name)} to ${id(x.name)}") }
    for (k in keys.created) {
        val add = if (k.primary) "add primary key (${ids(k.columns)})" else "add constraint ${id(k.name)} unique (${ids(k.columns)})"
        out += alter(*(if (k.primary && pkChange) arrayOf("drop primary key", add) else arrayOf(add)))
    }
    checksCreated.forEach { out += alter("add constraint ${id(it.name)} check (${it.expression})") }
    for (i in indexes.created) {
        val columns = i.columns.joinToString(", ") { c ->
            (if (c.expression) "(${c.name})" else id(c.name)) + when (c.order) { "DESC" -> " desc"; "ASC" -> " asc"; else -> "" }
        }
        out += "create ${if (i.unique) "unique " else ""}index ${id(i.name)} using ${i.method} on $q ($columns)"
    }
    for (f in fkCreated) {
        val ref = d.qualified(DbObject(if (f.refNs.catalog == null) o.ns else f.refNs, f.refTable, DbObjectKind.TABLE))
        out += alter("add constraint ${id(f.name)} foreign key (${ids(f.columns)}) references $ref (${ids(f.refColumns)}) on delete ${f.onDelete} on update ${f.onUpdate}")
    }
    // a column that is auto_increment now but was not, once the key that supports it is in place
    for (a in after.columns.filter { it.identity != null && it.generated == null }) {
        val b = kept.find { it.first === a }?.second
        if (b == null || b.identity == null) out += alter("modify column ${definition(a, auto = true)}")
    }

    // 4. the table itself, last so everything above uses the name it has now
    if (after.comment != before.comment) out += alter("comment = ${lit(after.comment.orEmpty())}")
    if (after.name != before.name) out += alter("rename to ${id(after.name)}")
    return out
}
