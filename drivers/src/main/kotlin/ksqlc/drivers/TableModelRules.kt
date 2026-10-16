package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.ModifyCaps

/** Why [m] cannot be applied yet, or null when it can. */
fun tableProblem(m: TableModel, caps: ModifyCaps = ModifyCaps()): String? {
    if (m.name.isBlank()) return "La tabla necesita un nombre"
    if (m.columns.isEmpty()) return "La tabla necesita al menos una columna"
    for (c in m.columns) {
        if (c.name.isBlank()) return "Una columna no tiene nombre"
        if (c.type.isBlank()) return "La columna ${c.name} no tiene tipo"
        if (c.identity != null && !c.notNull) return "La columna ${c.name} es identity y tiene que ser not null"
    }
    val names = m.columns.map { it.name.lowercase() }
    names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "La columna ${it.key} está repetida" }
    fun missing(cols: List<String>) = cols.firstOrNull { it.lowercase() !in names }
    if (m.keys.count { it.primary } > 1) return "Solo puede haber una clave primaria"
    for (k in m.keys) {
        if (k.name.isBlank()) return "Una clave no tiene nombre"
        if (k.columns.isEmpty()) return "La clave ${k.name} no tiene columnas"
        missing(k.columns)?.let { return "La clave ${k.name} usa la columna $it, que no existe" }
    }
    for (f in m.foreignKeys) {
        if (f.name.isBlank()) return "Una clave foránea no tiene nombre"
        if (f.columns.isEmpty()) return "La clave foránea ${f.name} no tiene columnas"
        missing(f.columns)?.let { return "La clave foránea ${f.name} usa la columna $it, que no existe" }
        if (f.refTable.isBlank()) return "La clave foránea ${f.name} no dice a qué tabla apunta"
        if (f.refColumns.size != f.columns.size) return "La clave foránea ${f.name} necesita tantas columnas referidas como propias"
    }
    for (c in m.checks) {
        if (c.name.isBlank()) return "Un check no tiene nombre"
        if (c.expression.isBlank()) return "El check ${c.name} no tiene expresión"
    }
    for (i in m.indexes) {
        if (i.name.isBlank()) return "Un índice no tiene nombre"
        if (i.columns.isEmpty() || i.columns.any { it.name.isBlank() }) return "El índice ${i.name} tiene una columna vacía o ninguna"
        missing(i.columns.filter { !it.expression }.map { it.name } + i.include)?.let { return "El índice ${i.name} usa la columna $it, que no existe" }
    }
    (m.keys.map { it.name } + m.foreignKeys.map { it.name } + m.checks.map { it.name }).groupBy { it.lowercase() }.entries
        .firstOrNull { it.value.size > 1 }?.let { return "El nombre de restricción ${it.key} está repetido" }
    if (caps.singleIdentity) {
        val autos = m.columns.filter { it.identity != null }
        if (autos.size > 1) return "Solo puede haber una columna ${caps.identityName}"
    }
    if (caps.identityLeadsKey) {
        m.columns.filter { it.identity != null }.singleOrNull()?.let { c ->
            val leads = m.keys.any { it.columns.firstOrNull().equals(c.name, ignoreCase = true) } ||
                m.indexes.any { ix -> ix.columns.firstOrNull()?.let { !it.expression && it.name.equals(c.name, ignoreCase = true) } == true }
            if (!leads) return "La columna ${c.name} es ${caps.identityName} y tiene que ser la primera de una clave o de un índice"
        }
    }
    return null
}

/** What applying [after] over [before] would lose, one sentence each: dropped columns and changed types. */
fun destructiveChanges(before: TableModel, after: TableModel): List<String> = buildList {
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }
        .forEach { add("Se elimina la columna ${it.name} con todos sus datos.") }
    for (a in after.columns) {
        val b = before.columns.find { it.name == a.origin } ?: continue
        if (b.type != a.type) add("Se cambia el tipo de la columna ${a.name} de ${b.type} a ${a.type}; los valores que no quepan darán error o se perderán.")
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
        if (unchanged && covers.none { it.take(f.columns.size) == f.columns }) return "La clave foránea ${f.name} necesita una clave o un índice que empiece por sus columnas"
    }
    return null
}
