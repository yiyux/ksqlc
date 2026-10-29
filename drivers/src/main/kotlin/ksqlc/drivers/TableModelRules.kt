package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.ModifyCaps

/** Why [m] cannot be applied yet, or null when it can. */
fun tableProblem(m: TableModel, caps: ModifyCaps = ModifyCaps()): String? {
    if (m.name.isBlank()) return tr("La tabla necesita un nombre")
    if (m.columns.isEmpty()) return tr("La tabla necesita al menos una columna")
    for (c in m.columns) {
        if (c.name.isBlank()) return tr("Una columna no tiene nombre")
        if (c.type.isBlank()) return tr("La columna %s no tiene tipo", c.name)
        if (c.identity != null && !c.notNull) return tr("La columna %s es identity y tiene que ser not null", c.name)
    }
    val names = m.columns.map { it.name.lowercase() }
    names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return tr("La columna %s está repetida", it.key) }
    fun missing(cols: List<String>) = cols.firstOrNull { it.lowercase() !in names }
    if (m.keys.count { it.primary } > 1) return tr("Solo puede haber una clave primaria")
    for (k in m.keys) {
        if (k.name.isBlank()) return tr("Una clave no tiene nombre")
        if (k.columns.isEmpty()) return tr("La clave %s no tiene columnas", k.name)
        missing(k.columns)?.let { return tr("La clave %s usa la columna %s, que no existe", k.name, it) }
    }
    for (f in m.foreignKeys) {
        if (f.name.isBlank()) return tr("Una clave foránea no tiene nombre")
        if (f.columns.isEmpty()) return tr("La clave foránea %s no tiene columnas", f.name)
        missing(f.columns)?.let { return tr("La clave foránea %s usa la columna %s, que no existe", f.name, it) }
        if (f.refTable.isBlank()) return tr("La clave foránea %s no dice a qué tabla apunta", f.name)
        if (f.refColumns.size != f.columns.size) return tr("La clave foránea %s necesita tantas columnas referidas como propias", f.name)
    }
    for (c in m.checks) {
        if (c.name.isBlank()) return tr("Un check no tiene nombre")
        if (c.expression.isBlank()) return tr("El check %s no tiene expresión", c.name)
    }
    for (i in m.indexes) {
        if (i.name.isBlank()) return tr("Un índice no tiene nombre")
        if (i.columns.isEmpty() || i.columns.any { it.name.isBlank() }) return tr("El índice %s tiene una columna vacía o ninguna", i.name)
        missing(i.columns.filter { !it.expression }.map { it.name } + i.include)?.let { return tr("El índice %s usa la columna %s, que no existe", i.name, it) }
    }
    (m.keys.map { it.name } + m.foreignKeys.map { it.name } + m.checks.map { it.name }).groupBy { it.lowercase() }.entries
        .firstOrNull { it.value.size > 1 }?.let { return tr("El nombre de restricción %s está repetido", it.key) }
    if (caps.singleIdentity) {
        val autos = m.columns.filter { it.identity != null }
        if (autos.size > 1) return tr("Solo puede haber una columna %s", caps.identityName)
    }
    if (caps.identityLeadsKey) {
        m.columns.filter { it.identity != null }.singleOrNull()?.let { c ->
            val leads = m.keys.any { it.columns.firstOrNull().equals(c.name, ignoreCase = true) } ||
                m.indexes.any { ix -> ix.columns.firstOrNull()?.let { !it.expression && it.name.equals(c.name, ignoreCase = true) } == true }
            if (!leads) return tr("La columna %s es %s y tiene que ser la primera de una clave o de un índice", c.name, caps.identityName)
        }
    }
    return null
}

/** What applying [after] over [before] would lose, one sentence each: dropped columns and changed types. */
fun destructiveChanges(before: TableModel, after: TableModel): List<String> = buildList {
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }
        .forEach { add(tr("Se elimina la columna %s con todos sus datos.", it.name)) }
    for (a in after.columns) {
        val b = before.columns.find { it.name == a.origin } ?: continue
        if (b.type != a.type) add(tr("Se cambia el tipo de la columna %s de %s a %s; los valores que no quepan darán error o se perderán.", a.name, b.type, a.type))
    }
}

/**
 * Engines that do not make an index for a foreign key that already exists (MySQL, MariaDB) refuse to drop the one it rests on (1553):
 * why a foreign key left as it was has no key or index starting with its columns in [after], or null.
 */
fun fkIndexProblem(before: TableModel, after: TableModel): String? {
    val renamed = after.columns.filter { it.origin != null }.associate { it.origin!! to it.name }
    val covers = after.keys.map { it.columns } + after.indexes.map { ix -> ix.columns.map { it.name } }
    for (f in after.foreignKeys) {
        val old = before.foreignKeys.find { it.name == f.origin } ?: continue
        // a foreign key that was changed is dropped and added again, and the server makes its index then
        val unchanged = old.columns.map { renamed[it] } == f.columns && old.refNs == f.refNs && old.refTable == f.refTable &&
            old.refColumns == f.refColumns && old.onDelete == f.onDelete && old.onUpdate == f.onUpdate
        if (unchanged && covers.none { it.take(f.columns.size) == f.columns }) return tr("La clave foránea %s necesita una clave o un índice que empiece por sus columnas", f.name)
    }
    return null
}
