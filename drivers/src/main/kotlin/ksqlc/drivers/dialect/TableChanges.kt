package ksqlc.drivers.dialect

import ksqlc.core.TableModel

internal class Changes<T>(val dropped: List<T>, val created: List<T>, val renamed: List<Pair<T, T>>)

/** Pairs each [after] element with its [before] one by origin: a changed definition is dropped and created again, a new name alone is a rename. */
internal fun <T> changes(before: List<T>, after: List<T>, origin: (T) -> String?, name: (T) -> String, same: (T, T) -> Boolean): Changes<T> {
    val pairs = after.mapNotNull { x -> before.find { name(it) == origin(x) }?.let { it to x } }
    val changed = pairs.filter { (y, x) -> !same(y, x) }
    return Changes(
        dropped = before.filter { y -> after.none { origin(it) == name(y) } } + changed.map { it.first },
        created = after.filter { origin(it) == null } + changed.map { it.second },
        renamed = pairs.filter { (y, x) -> same(y, x) && name(y) != name(x) },
    )
}

/** How the columns moved between [before] and [after]: which kept their identity (and maybe a new name) and which are gone. */
internal class ColumnMoves(before: TableModel, after: TableModel) {
    val kept = after.columns.mapNotNull { a -> before.columns.find { it.name == a.origin }?.let { a to it } }
    val renamed = kept.filter { (a, b) -> a.name != b.name }.associate { (a, b) -> b.name to a.name }
    val gone = before.columns.map { it.name }.filter { n -> after.columns.none { it.origin == n } }

    /** A dropped column takes its constraints with it, so anything using it is dropped and created again even if a new column reuses the name. */
    fun mapped(l: List<String>) = l.map { if (it in gone) "\u0000$it" else renamed[it] ?: it }
    fun mentionsGone(text: String?) = text != null && gone.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
}
