package ksqlc.drivers.dialect

import ksqlc.core.*

/**
 * The T-SQL that turns [before] into [after] on table [o]; empty when nothing changed. SQL Server's DDL is transactional, so
 * the whole list runs in one transaction. What the server refuses (checked against 2022):
 * - dropping a column that has a default, an index, a key or a foreign key, or changing its type (or nullability) while an index,
 *   key or foreign key uses it, or its type while it has a default: those are dropped first and created again;
 * - renaming a column that a check, a filtered index or a computed column uses: the check and the index are dropped and created
 *   again with the new name; a computed column that uses it is left for the server to refuse.
 */
internal fun sqlServerAlterTable(d: Dialect, o: DbObject, before: TableModel, after: TableModel): List<String> {
    val q = d.qualified(o)
    val schema = o.ns.schema.orEmpty()
    fun id(n: String) = d.quote(n)
    fun ids(l: List<String>) = l.joinToString(", ") { d.quote(it) }
    fun lit(s: String) = "N'" + s.replace("'", "''") + "'"
    fun alter(clause: String) = "alter table $q $clause"
    /** `[schema].[table].[x]` as the text `sp_rename` takes. */
    fun path(vararg parts: String) = lit(parts.joinToString(".") { id(it) })
    fun rename(path: String, newName: String, type: String? = null) = "exec sp_rename $path, ${lit(newName)}" + (type?.let { ", ${lit(it)}" } ?: "")

    fun property(proc: String, text: String?, column: String?): String {
        val level = "N'SCHEMA', ${lit(schema)}, N'TABLE', ${lit(o.name)}" + (column?.let { ", N'COLUMN', ${lit(it)}" } ?: "")
        return "exec $proc N'MS_Description', " + (text?.let { "${lit(it)}, " } ?: "") + level
    }
    fun comment(was: String?, now: String?, column: String?): String? = when {
        was == now -> null
        now == null -> property("sp_dropextendedproperty", null, column)
        was == null -> property("sp_addextendedproperty", now, column)
        else -> property("sp_updateextendedproperty", now, column)
    }

    /** A default is a constraint with a name the server chose, so it is looked up by its column and dropped by that name. */
    fun dropDefault(column: String) =
        "declare @n sysname = (select dc.name from sys.default_constraints dc where dc.parent_object_id = object_id(${lit(q)}) " +
            "and dc.parent_column_id = columnproperty(object_id(${lit(q)}), ${lit(column)}, 'ColumnId')); " +
            // exec() takes no function calls, so the text is built in a variable first
            "if @n is not null begin declare @s nvarchar(max) = N'alter table ' + ${lit(q)} + N' drop constraint ' + quotename(@n); exec(@s) end"

    /** The column as `add` takes it. */
    fun definition(c: ColumnModel): String = buildString {
        append(id(c.name), " ")
        val gen = c.generated
        if (gen != null) { append("as ($gen)"); if (c.generatedStored) append(" persisted"); return@buildString }
        append(c.type)
        if (c.identity != null) append(" identity(1,1)")
        append(if (c.notNull) " not null" else " null")
        c.default?.let { append(" default ($it)") }
    }

    val moves = ColumnMoves(before, after)
    val kept = moves.kept
    kept.firstOrNull { (a, b) -> (a.identity != null) != (b.identity != null) }
        ?.let { error(tr("SQL Server no puede añadir ni quitar identity a la columna %s, que ya existe", it.first.name)) }
    // a computed column cannot be altered, so its type and nullability do not count
    val altered = kept.filter { (a, b) -> a.generated == null && (a.type != b.type || a.notNull != b.notNull) }.map { it.second.name }.toSet()
    fun mapped(l: List<String>) = moves.mapped(l)

    // a check or a filter keeps its text, which the catalog writes with `[bracketed]` names; a name inside a literal is not a use of the column
    val renamedLower = moves.renamed.mapKeys { it.key.lowercase() }
    val renameRegex = moves.renamed.keys.takeIf { it.isNotEmpty() }?.joinToString("|") { Regex.escape("[$it]") }
        ?.let { Regex("'(?:[^']|'')*'|$it", RegexOption.IGNORE_CASE) }
    fun renamedIn(e: String) = renameRegex?.replace(e) { m ->
        if (m.value.startsWith("'")) m.value else "[" + renamedLower.getValue(m.value.substring(1, m.value.length - 1).lowercase()) + "]"
    } ?: e
    val unsafe = moves.gone + altered
    fun mentionsUnsafe(e: String) = unsafe.any { Regex("""\[${Regex.escape(it)}]|\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(e) }
    fun touched(e: String) = renamedIn(e) != e || mentionsUnsafe(e)

    val keys = changes(before.keys, after.keys, { it.origin }, { it.name }) { y, x ->
        y.columns.none { it in altered } && y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val fks = changes(before.foreignKeys, after.foreignKeys, { it.origin }, { it.name }) { y, x ->
        y.columns.none { it in altered } && y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val checks = changes(before.checks, after.checks, { it.origin }, { it.name }) { y, x -> y.expression == x.expression && !touched(y.expression) }
    val checksCreated = checks.created.map { x ->
        val y = before.checks.find { it.name == x.origin }
        if (y != null && y.expression == x.expression) x.copy(expression = renamedIn(x.expression)) else x
    }
    val indexes = changes(before.indexes, after.indexes, { it.origin }, { it.name }) { y, x ->
        y.columns.none { it.name in altered } && y.include.none { it in altered } && y.condition?.let(::touched) != true &&
            y.copy(origin = null, name = "", columns = y.columns.map { it.copy(name = mapped(listOf(it.name)).single()) }, include = mapped(y.include)) == x.copy(origin = null, name = "")
    }
    val indexesCreated = indexes.created.map { x ->
        val y = before.indexes.find { it.name == x.origin }
        val cond = x.condition
        if (y != null && cond != null && y.condition == cond) x.copy(condition = renamedIn(cond)) else x
    }
    /** A default goes and comes back when its text or its column's type changes (the type cannot change under it). */
    fun defaultMoves(a: ColumnModel, b: ColumnModel) = a.generated == null && (a.default != b.default || a.type != b.type)

    val out = ArrayList<String>()

    // 1. what goes away (by the names it has now)
    fks.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    checks.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    for ((a, b) in kept) if (b.default != null && defaultMoves(a, b)) out += dropDefault(b.name)
    for (b in before.columns.filter { it.name in moves.gone && it.default != null }) out += dropDefault(b.name)
    keys.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    indexes.dropped.forEach { out += "drop index ${id(it.name)} on $q" }

    // 2. columns
    moves.gone.forEach { out += alter("drop column ${id(it)}") }
    for ((a, b) in kept) if (a.name != b.name) out += rename(path(o.ns.schema.orEmpty(), o.name, b.name), a.name, "COLUMN")
    after.columns.filter { it.origin == null }.forEach { out += alter("add ${definition(it)}") }
    for ((a, b) in kept) {
        if (b.name in altered) out += alter("alter column ${id(a.name)} ${a.type} ${if (a.notNull) "not null" else "null"}")
        if (a.default != null && defaultMoves(a, b)) out += alter("add default (${a.default}) for ${id(a.name)}")
    }

    // 3. what is only renamed, then what is new or changed
    keys.renamed.forEach { (y, x) -> out += rename(path(schema, y.name), x.name, "OBJECT") }
    fks.renamed.forEach { (y, x) -> out += rename(path(schema, y.name), x.name, "OBJECT") }
    checks.renamed.forEach { (y, x) -> out += rename(path(schema, y.name), x.name, "OBJECT") }
    indexes.renamed.forEach { (y, x) -> out += rename(path(schema, o.name, y.name), x.name, "INDEX") }
    for (k in keys.created) {
        val clustered = when (k.clustered) { true -> " clustered"; false -> " nonclustered"; null -> "" }
        out += alter("add constraint ${id(k.name)} ${if (k.primary) "primary key" else "unique"}$clustered (${ids(k.columns)})")
    }
    for (i in indexesCreated) {
        val cols = i.columns.joinToString(", ") { c -> id(c.name) + when (c.order) { "DESC" -> " desc"; "ASC" -> " asc"; else -> "" } }
        out += "create ${if (i.unique) "unique " else ""}${if (i.method == "clustered") "clustered" else "nonclustered"} index ${id(i.name)} on $q ($cols)" +
            (if (i.include.isNotEmpty()) " include (${ids(i.include)})" else "") + (i.condition?.let { " where $it" } ?: "")
    }
    checksCreated.forEach { out += alter("add constraint ${id(it.name)} check (${it.expression})") }
    for (f in fks.created) {
        val ref = d.qualified(DbObject(if (f.refNs.schema == null) o.ns else f.refNs, f.refTable, DbObjectKind.TABLE))
        out += alter("add constraint ${id(f.name)} foreign key (${ids(f.columns)}) references $ref (${ids(f.refColumns)}) on delete ${f.onDelete} on update ${f.onUpdate}")
    }

    // 4. comments, then the table itself, last so everything above uses the name it has now
    for (c in after.columns.filter { it.origin == null }) comment(null, c.comment, c.name)?.let { out += it }
    for ((a, b) in kept) comment(b.comment, a.comment, a.name)?.let { out += it }
    comment(before.comment, after.comment, null)?.let { out += it }
    if (after.name != before.name) out += rename(path(schema, o.name), after.name)
    return out
}
