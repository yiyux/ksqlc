package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.fkIndexProblem
import ksqlc.drivers.tableProblem

/** The selected node of the Modify tree: [group] is "table", "columns", "keys", "fks", "indexes" or "checks"; [index] is -1 on the table and group rows. */
data class Sel(val group: String, val index: Int = -1)

internal fun <T> List<T>.replaced(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (to !in indices || from !in indices) this else toMutableList().also { it.add(to, it.removeAt(from)) }

/** The table being modified: what the dialog edits, and the SQL that editing amounts to. */
class ModifyDraft(val obj: DbObject, val before: TableModel, private val dialect: Dialect) {
    var model by mutableStateOf(before)
        private set
    var selected by mutableStateOf(Sel("table"))

    val caps get() = dialect.modifyCaps
    val problem: String? get() = tableProblem(model, caps) ?: if (caps.fkNeedsIndex) fkIndexProblem(before, model) else null

    /** What the preview says before the SQL: that applying is not all or nothing, and what the dialog cannot hold and so leaves alone. */
    val notice: String? get() = listOfNotNull(
        if (caps.atomic) null else "MySQL y MariaDB confirman cada sentencia: si una falla, las anteriores quedan aplicadas.",
        before.skipped.takeIf { it.isNotEmpty() }?.let { "Fuera de este diálogo y sin tocar: ${it.joinToString(", ")}." },
    ).takeIf { it.isNotEmpty() }?.joinToString("\n-- ")

    /** The SQL OK would run; empty while there is a [problem] or nothing changed. */
    val statements: List<String> get() = if (problem != null) emptyList() else dialect.alterTable(obj, before, model)

    fun rename(name: String) { model = model.copy(name = name) }
    fun comment(text: String) { model = model.copy(comment = text.ifBlank { null }) }

    /** The name under which keys, foreign keys and indexes refer to column i; parallel to model.columns, so references follow the column and not its name. */
    private var refNames = before.columns.map { it.name }
    private var aliases = 0

    /** Renaming a column rewrites the keys, foreign keys and indexes that use it, so they stay the same element. */
    fun updateColumn(i: Int, f: (ColumnModel) -> ColumnModel) {
        val new = f(model.columns[i])
        val oldRef = refNames[i]
        // A name another column has gets a placeholder reference, so that column's references are never touched.
        val newRef = if (model.columns.withIndex().any { (n, c) -> n != i && c.name.equals(new.name, ignoreCase = true) }) "\u0000${aliases++}" else new.name
        var m = model.copy(columns = model.columns.replaced(i, new))
        if (newRef != oldRef) {
            fun r(l: List<String>) = l.map { if (it == oldRef) newRef else it }
            m = m.copy(
                keys = m.keys.map { it.copy(columns = r(it.columns)) },
                foreignKeys = m.foreignKeys.map { it.copy(columns = r(it.columns)) },
                indexes = m.indexes.map { ix ->
                    ix.copy(columns = ix.columns.map { c -> if (c.expression || c.name != oldRef) c else c.copy(name = newRef) }, include = r(ix.include))
                },
            )
        }
        refNames = refNames.replaced(i, newRef)
        // an alias whose collision is over goes back to the real name
        for (j in refNames.indices) {
            val alias = refNames[j]
            val name = m.columns[j].name
            if (!alias.startsWith("\u0000") || m.columns.count { it.name.equals(name, ignoreCase = true) } != 1) continue
            fun r(l: List<String>) = l.map { if (it == alias) name else it }
            m = m.copy(
                keys = m.keys.map { it.copy(columns = r(it.columns)) },
                foreignKeys = m.foreignKeys.map { it.copy(columns = r(it.columns)) },
                indexes = m.indexes.map { ix ->
                    ix.copy(columns = ix.columns.map { c -> if (c.expression || c.name != alias) c else c.copy(name = name) }, include = r(ix.include))
                },
            )
            refNames = refNames.replaced(j, name)
        }
        model = m
    }

    fun updateKey(i: Int, f: (KeyModel) -> KeyModel) { model = model.copy(keys = model.keys.replaced(i, f(model.keys[i]))) }
    fun updateForeignKey(i: Int, f: (ForeignKeyModel) -> ForeignKeyModel) { model = model.copy(foreignKeys = model.foreignKeys.replaced(i, f(model.foreignKeys[i]))) }
    fun updateIndex(i: Int, f: (IndexModel) -> IndexModel) { model = model.copy(indexes = model.indexes.replaced(i, f(model.indexes[i]))) }
    fun updateCheck(i: Int, f: (CheckModel) -> CheckModel) { model = model.copy(checks = model.checks.replaced(i, f(model.checks[i]))) }

    private fun unique(base: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in used) return base
        var n = 2
        while ("${base}_$n".lowercase() in used) n++
        return "${base}_$n"
    }

    private fun constraintNames() = model.keys.map { it.name } + model.foreignKeys.map { it.name } + model.checks.map { it.name } + model.indexes.map { it.name }

    fun add(group: String) {
        val first = listOfNotNull(model.columns.firstOrNull()?.name)
        val t = model.name
        when (group) {
            "columns" -> {
                val c = ColumnModel(null, unique("column_name", model.columns.map { it.name }), "integer")
                model = model.copy(columns = model.columns + c); refNames = refNames + c.name
            }
            "keys" -> model = model.copy(keys = model.keys + KeyModel(null, unique("${t}_key", constraintNames()), false, first))
            "fks" -> model = model.copy(foreignKeys = model.foreignKeys + ForeignKeyModel(null, unique("${t}_fk", constraintNames()), first, obj.ns, "", emptyList()))
            "indexes" -> model = model.copy(indexes = model.indexes + IndexModel(null, unique("${t}_idx", constraintNames()), false, "btree", first.map { IndexColumn(it) }))
            "checks" -> model = model.copy(checks = model.checks + CheckModel(null, unique("${t}_check", constraintNames()), ""))
            else -> return
        }
        selected = Sel(group, count(group) - 1)
    }

    private fun count(group: String) = when (group) {
        "columns" -> model.columns.size; "keys" -> model.keys.size; "fks" -> model.foreignKeys.size
        "indexes" -> model.indexes.size; "checks" -> model.checks.size; else -> 0
    }

    private fun <T> List<T>.without(i: Int) = filterIndexed { n, _ -> n != i }

    fun remove(sel: Sel) {
        val i = sel.index
        if (i < 0 || i >= count(sel.group)) return
        model = when (sel.group) {
            "columns" -> model.copy(columns = model.columns.without(i)).also { refNames = refNames.without(i) }
            "keys" -> model.copy(keys = model.keys.without(i))
            "fks" -> model.copy(foreignKeys = model.foreignKeys.without(i))
            "indexes" -> model.copy(indexes = model.indexes.without(i))
            else -> model.copy(checks = model.checks.without(i))
        }
        selected = Sel(sel.group)
    }

    fun duplicate(sel: Sel) {
        val i = sel.index
        if (i < 0 || i >= count(sel.group)) return
        when (sel.group) {
            "columns" -> model.columns[i].let { c ->
                val copy = c.copy(origin = null, name = unique("${c.name}_copy", model.columns.map { it.name }))
                model = model.copy(columns = model.columns + copy); refNames = refNames + copy.name
            }
            "keys" -> model.keys[i].let { k -> model = model.copy(keys = model.keys + k.copy(origin = null, primary = false, name = unique("${k.name}_copy", constraintNames()))) }
            "fks" -> model.foreignKeys[i].let { f -> model = model.copy(foreignKeys = model.foreignKeys + f.copy(origin = null, name = unique("${f.name}_copy", constraintNames()))) }
            "indexes" -> model.indexes[i].let { x -> model = model.copy(indexes = model.indexes + x.copy(origin = null, name = unique("${x.name}_copy", constraintNames()))) }
            else -> model.checks[i].let { c -> model = model.copy(checks = model.checks + c.copy(origin = null, name = unique("${c.name}_copy", constraintNames()))) }
        }
        selected = Sel(sel.group, count(sel.group) - 1)
    }
}
