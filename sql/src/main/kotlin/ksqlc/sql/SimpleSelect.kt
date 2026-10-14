package ksqlc.sql

private val NOT_SIMPLE = setOf("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "LATERAL", "UNION", "INTERSECT", "EXCEPT", "MINUS", "GROUP", "HAVING", "WINDOW")

/**
 * The table of a plain `select <columns|*> from <table> [alias] [where …] [order by …] [limit …]`, whose rows are rows of that
 * table and whose columns keep their names; null for anything else (joins, set operations, grouping, distinct, expressions,
 * aliased columns, subqueries). Conservative on purpose: its answer decides whether result rows can be edited back.
 */
fun simpleSelectTable(sql: String): QueryTable? {
    val range = StatementRange(0, sql.length, sql.length)
    val t = significant(sql, range)
    if (t.size < 4 || t[0].upper(sql) != "SELECT") return null
    val from = t.indexOfFirst { it.kind == TokenKind.KEYWORD && it.upper(sql) == "FROM" }
    if (from < 2) return null
    // select list: `*`, `name`, `t.name` or `t.*`, separated by commas; nothing else (no AS, calls, DISTINCT, TOP…)
    val items = ArrayList<MutableList<Token>>().apply { add(mutableListOf()) }
    for (k in 1 until from) {
        val tok = t[k]
        if (tok.isPunct(sql, ',')) items.add(mutableListOf()) else items.last().add(tok)
    }
    fun isName(x: Token) = x.isIdent() || x.kind == TokenKind.TYPE
    fun isStar(x: Token) = sql.substring(x.start, x.end) == "*"
    val plain = items.all { it.size == 1 && (isName(it[0]) || isStar(it[0])) || it.size == 3 && isName(it[0]) && it[1].isPunct(sql, '.') && (isName(it[2]) || isStar(it[2])) }
    if (!plain) return null
    val after = t.drop(from + 1)
    if (after.any { it.kind == TokenKind.KEYWORD && it.upper(sql) in NOT_SIMPLE }) return null
    val refs = tableRefs(sql, range)
    return refs.singleOrNull()?.takeIf { !it.isCte }
}
