package ksqlc.sql

/** A parameter in SQL text: `:name` (then [name] is `:name`) or a bare `?` ([name] is `?`); [start, end) is its place. */
data class ParamRef(val name: String, val start: Int, val end: Int)

private val NAMED = Regex("""(?<![:\w\])])(:[A-Za-z_]\w*)""")
private val OLD_NEW = setOf(":new", ":old")      // Oracle trigger bodies, not parameters

/**
 * The `:name` parameters of [sql], plus the `?` ones when [positional] is set (not for PostgreSQL, where `?` is a JSON operator).
 * Text inside strings, quoted names and comments does not count, nor does a `::` cast.
 */
fun findParams(sql: String, positional: Boolean): List<ParamRef> {
    val code = BooleanArray(sql.length) { true }
    for (t in tokenize(sql)) if (t.kind == TokenKind.STRING || t.kind == TokenKind.COMMENT || t.kind == TokenKind.QUOTED_IDENT) for (i in t.start until t.end) code[i] = false
    val out = ArrayList<ParamRef>()
    for (m in NAMED.findAll(sql)) if (code[m.range.first] && m.value.lowercase() !in OLD_NEW) out += ParamRef(m.value, m.range.first, m.range.last + 1)
    if (positional) for (i in sql.indices) if (sql[i] == '?' && code[i]) out += ParamRef("?", i, i + 1)
    return out.sortedBy { it.start }
}

/** [sql] with each of [refs] (as [findParams] returned them, in order) replaced by the matching entry of [values]. */
fun fillParams(sql: String, refs: List<ParamRef>, values: List<String>): String = buildString {
    var at = 0
    refs.forEachIndexed { i, r -> append(sql, at, r.start).append(values[i]); at = r.end }
    append(sql, at, sql.length)
}
