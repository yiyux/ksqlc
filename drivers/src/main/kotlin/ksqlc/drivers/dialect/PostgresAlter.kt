package ksqlc.drivers.dialect

import ksqlc.core.*

private fun identityOptions(i: Identity): String = listOfNotNull(
    i.step?.let { "increment by $it" }, i.min?.let { "minvalue $it" }, i.max?.let { "maxvalue $it" }, i.cache?.let { "cache $it" },
    if (i.cycle) "cycle" else null,
).takeIf { it.isNotEmpty() }?.joinToString(" ", " (", ")").orEmpty()

private fun deferral(deferrable: Boolean, initially: Boolean) = if (!deferrable) "" else if (initially) " deferrable initially deferred" else " deferrable"

internal fun postgresAlterTable(d: Dialect, o: DbObject, before: TableModel, after: TableModel): List<String> {
    val schema = o.ns.schema ?: error("La tabla no tiene esquema")
    val q = d.qualified(o)
    fun id(n: String) = d.quote(n)
    fun ids(l: List<String>) = l.joinToString(", ") { d.quote(it) }
    fun lit(s: String?) = if (s == null) "null" else "'" + s.replace("'", "''") + "'"
    fun index(n: String) = "${d.quote(schema)}.${d.quote(n)}"
    fun alter(clause: String) = "alter table $q $clause"
    val out = ArrayList<String>()

    val moves = ColumnMoves(before, after)
    val kept = moves.kept
    fun mapped(l: List<String>) = moves.mapped(l)
    fun mentionsGone(text: String?) = moves.mentionsGone(text)

    val keys = changes(before.keys, after.keys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val foreignKeys = changes(before.foreignKeys, after.foreignKeys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val checks = changes(before.checks, after.checks, { it.origin }, { it.name }) { y, x -> y.expression == x.expression && !mentionsGone(y.expression) }
    val indexes = changes(before.indexes, after.indexes, { it.origin }, { it.name }) { y, x ->
        val columns = y.columns.map { if (it.expression) it else it.copy(name = mapped(listOf(it.name)).single()) }
        y.columns.none { it.expression && mentionsGone(it.name) } && !mentionsGone(y.condition) &&
            y.copy(origin = null, name = "", columns = columns, include = mapped(y.include)) == x.copy(origin = null, name = "")
    }

    // 1. what goes away
    foreignKeys.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    checks.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    keys.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    indexes.dropped.forEach { out += "drop index ${index(it.name)}" }

    // 2. columns
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }.forEach { out += alter("drop column ${id(it.name)}") }
    // renames before the adds, so a new column can take the old name of a renamed one
    for ((a, b) in kept) if (a.name != b.name) out += alter("rename column ${id(b.name)} to ${id(a.name)}")
    for (c in after.columns.filter { it.origin == null }) {
        val ident = c.identity
        val gen = c.generated
        val def = c.default
        out += alter("add column " + buildString {
            append(id(c.name), " ", c.type)
            when {
                gen != null -> append(" generated always as ($gen) stored")
                ident != null -> append(" generated ", if (ident.always) "always" else "by default", " as identity", identityOptions(ident))
                def != null -> append(" default ", def)
            }
            if (c.notNull) append(" not null")
        })
        c.comment?.let { out += "comment on column $q.${id(c.name)} is ${lit(it)}" }
    }
    for ((a, b) in kept) {
        val c = id(a.name)
        // PostgreSQL refuses not null / default changes on a column that is still identity
        if (a.identity == null && b.identity != null) out += alter("alter column $c drop identity")
        val retyped = a.type != b.type
        if (retyped && b.default != null) out += alter("alter column $c drop default")
        if (retyped) out += alter("alter column $c type ${a.type} using $c::${a.type}")
        if (a.notNull != b.notNull) out += alter("alter column $c ${if (a.notNull) "set" else "drop"} not null")
        val def = a.default
        if (def != null && (retyped || def != b.default)) out += alter("alter column $c set default $def")
        else if (def == null && b.default != null && !retyped) out += alter("alter column $c drop default")
        val ai = a.identity
        val bi = b.identity
        when {
            ai != null && bi == null -> out += alter("alter column $c add generated ${if (ai.always) "always" else "by default"} as identity${identityOptions(ai)}")
            ai != null && bi != null -> {
                if (ai.always != bi.always) out += alter("alter column $c set generated ${if (ai.always) "always" else "by default"}")
                if (ai.step != bi.step && ai.step != null) out += alter("alter column $c set increment by ${ai.step}")
                if (ai.min != bi.min) out += alter("alter column $c " + (ai.min?.let { "set minvalue $it" } ?: "set no minvalue"))
                if (ai.max != bi.max) out += alter("alter column $c " + (ai.max?.let { "set maxvalue $it" } ?: "set no maxvalue"))
                if (ai.cache != bi.cache && ai.cache != null) out += alter("alter column $c set cache ${ai.cache}")
                if (ai.cycle != bi.cycle) out += alter("alter column $c set ${if (ai.cycle) "cycle" else "no cycle"}")
            }
        }
        if (a.comment != b.comment) out += "comment on column $q.$c is ${lit(a.comment)}"
    }

    // 3. what is new or only renamed
    keys.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    checks.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    foreignKeys.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    indexes.renamed.forEach { (y, x) -> out += "alter index ${index(y.name)} rename to ${id(x.name)}" }
    keys.created.forEach { k ->
        out += alter("add constraint ${id(k.name)} ${if (k.primary) "primary key" else "unique"} (${ids(k.columns)})${deferral(k.deferrable, k.initiallyDeferred)}")
    }
    checks.created.forEach { out += alter("add constraint ${id(it.name)} check (${it.expression})") }
    indexes.created.forEach { i ->
        val columns = i.columns.joinToString(", ") { c ->
            buildString {
                append(if (c.expression) "(${c.name})" else id(c.name))
                c.collation?.let { append(" collate ", id(it)) }
                c.opClass?.let { append(" ", it) }
                if (c.order.isNotEmpty()) append(" ", c.order)
            }
        }
        out += "create ${if (i.unique) "unique " else ""}index ${id(i.name)} on $q using ${i.method} ($columns)" +
            (if (i.include.isNotEmpty()) " include (${ids(i.include)})" else "") + (i.condition?.let { " where $it" } ?: "")
    }
    foreignKeys.created.forEach { f ->
        val ref = d.qualified(DbObject(f.refNs, f.refTable, DbObjectKind.TABLE))
        out += alter("add constraint ${id(f.name)} foreign key (${ids(f.columns)}) references $ref (${ids(f.refColumns)}) on delete ${f.onDelete} on update ${f.onUpdate}${deferral(f.deferrable, f.initiallyDeferred)}")
    }

    // 4. the table itself, last so everything above uses the name it has now
    if (after.comment != before.comment) out += "comment on table $q is ${lit(after.comment)}"
    if (after.name != before.name) out += alter("rename to ${id(after.name)}")
    return out
}
