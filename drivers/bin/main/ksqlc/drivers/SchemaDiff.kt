package ksqlc.drivers

import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.core.Namespace

enum class ObjectStatus { ONLY_LEFT, ONLY_RIGHT, DIFFERENT, SAME }

/** One object compared across two schemas. [left] / [right] are the normalized DDL, null where the object does not exist. */
data class ObjectDiff(val kind: DbObjectKind, val name: String, val status: ObjectStatus, val left: String?, val right: String?)

/**
 * Removes the schema (or database) name that prefixes objects in [ddl], quoted in any of the engines' styles, and
 * trailing blanks, so two schemas with different names but the same content compare equal.
 */
fun normalizeDdl(ddl: String, ns: Namespace): String {
    var out = ddl.replace("\r\n", "\n")
    for (name in listOfNotNull(ns.schema, ns.catalog).filter { it.isNotEmpty() }.distinct()) {
        val q = Regex.escape(name)
        out = out.replace(Regex("""(?<![\w"`\]])(?:"$q"|`$q`|\[$q]|$q)\."""), "")
    }
    return out.lines().joinToString("\n") { it.trimEnd() }.trim()
}

/**
 * Compares the objects of [leftNs] in [left] with those of [rightNs] in [right], matching them by kind and name (ignoring
 * case). Reads each object's DDL; one that cannot be read shows the error as its text. Sorted by kind then name.
 * [onProgress] gets (done, total); stop early by returning true from [cancelled] (the partial list is returned).
 */
fun diffSchemas(
    left: SchemaSource, leftNs: Namespace, right: SchemaSource, rightNs: Namespace,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
): List<ObjectDiff> {
    fun key(o: DbObject) = o.kind to o.name.lowercase()
    val l = left.objects(leftNs).associateBy(::key)
    val r = right.objects(rightNs).associateBy(::key)
    val keys = (l.keys + r.keys).sortedWith(compareBy({ it.first.ordinal }, { it.second }))
    fun ddl(src: SchemaSource, o: DbObject?, ns: Namespace) =
        o?.let { normalizeDdl(runCatching { src.ddl(it) }.getOrElse { e -> "-- no se pudo leer el DDL: ${e.message}" }, ns) }
    val out = ArrayList<ObjectDiff>()
    keys.forEachIndexed { i, k ->
        if (cancelled()) return out
        val lo = l[k]; val ro = r[k]
        val ld = ddl(left, lo, leftNs); val rd = ddl(right, ro, rightNs)
        val status = when { ld == null -> ObjectStatus.ONLY_RIGHT; rd == null -> ObjectStatus.ONLY_LEFT; ld == rd -> ObjectStatus.SAME; else -> ObjectStatus.DIFFERENT }
        out += ObjectDiff(k.first, (lo ?: ro)!!.name, status, ld, rd)
        onProgress(i + 1, keys.size)
    }
    return out
}
