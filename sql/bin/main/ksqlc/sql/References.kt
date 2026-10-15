package ksqlc.sql

/** A table (or CTE) used by a statement: [start, end) covers its possibly qualified name. */
data class QueryTable(val schema: String?, val name: String, val alias: String?, val start: Int, val end: Int, val isCte: Boolean = false) {
    fun answersTo(q: String) = q.equals(alias ?: name, true) || (alias != null && q.equals(name, true))
}

/** Significant tokens (no whitespace, no comments) of [range] within [sql]. */
internal fun significant(sql: String, range: StatementRange): List<Token> =
    tokenize(sql.substring(range.start, range.end)).filter { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT }
        .map { Token(it.kind, it.start + range.start, it.end + range.start) }

internal fun Token.isIdent() = kind == TokenKind.IDENT || kind == TokenKind.QUOTED_IDENT
internal fun Token.isPunct(sql: String, ch: Char) = kind == TokenKind.PUNCT && sql[start] == ch
internal fun Token.upper(sql: String) = sql.substring(start, end).uppercase()

private val TABLE_KEYWORDS = setOf("FROM", "JOIN", "UPDATE", "INTO")
private val FUNCTION_FROM = setOf("EXTRACT", "SUBSTRING", "TRIM", "OVERLAY")

/** Tables named by FROM / JOIN / UPDATE / INTO in [range], with their aliases, plus the CTE names the statement defines. */
fun tableRefs(sql: String, range: StatementRange): List<QueryTable> {
    val t = significant(sql, range)
    val out = ArrayList<QueryTable>()
    // CTE names: `name [(cols)] AS (`
    for (i in 0 until t.size - 2) {
        if (t[i].isIdent() && t[i + 1].kind == TokenKind.KEYWORD && t[i + 1].upper(sql) == "AS" && t[i + 2].isPunct(sql, '(')) {
            out += QueryTable(null, unquote(t[i].text(sql)), null, t[i].start, t[i].end, isCte = true)
        }
    }
    val parens = ArrayList<String>()   // word before each open paren
    var i = 0
    while (i < t.size) {
        val tok = t[i]
        if (tok.isPunct(sql, '(')) parens += if (i > 0) t[i - 1].upper(sql) else ""
        else if (tok.isPunct(sql, ')') && parens.isNotEmpty()) parens.removeLast()
        if (tok.kind == TokenKind.KEYWORD && tok.upper(sql) in TABLE_KEYWORDS) {
            val kw = tok.upper(sql)
            val skip = (kw == "FROM" && ((parens.lastOrNull() in FUNCTION_FROM) || (i > 0 && t[i - 1].upper(sql) == "DISTINCT")))
            if (!skip) {
                var j = i + 1
                while (true) {
                    val r = parseTable(sql, t, j, allowParenAfter = kw == "INTO") ?: break
                    out += r.first
                    j = r.second
                    if (kw == "FROM" && j < t.size && t[j].isPunct(sql, ',')) j++ else break
                }
            }
        }
        i++
    }
    return out
}

private fun parseTable(sql: String, t: List<Token>, from: Int, allowParenAfter: Boolean): Pair<QueryTable, Int>? {
    var j = from
    if (j >= t.size || !t[j].isIdent()) return null
    val parts = mutableListOf(t[j]); j++
    while (j + 1 < t.size && t[j].isPunct(sql, '.') && t[j + 1].isIdent()) { parts += t[j + 1]; j += 2 }
    if (j < t.size && t[j].isPunct(sql, '(') && !allowParenAfter) return null   // table function
    var alias: String? = null
    if (j < t.size && !(t[j].isPunct(sql, '('))) {
        var k = j
        if (t[k].kind == TokenKind.KEYWORD && t[k].upper(sql) == "AS") k++
        if (k < t.size && t[k].isIdent()) { alias = unquote(t[k].text(sql)); j = k + 1 }
    }
    val names = parts.map { unquote(it.text(sql)) }
    val q = QueryTable(if (names.size > 1) names[names.size - 2] else null, names.last(), alias, parts.first().start, parts.last().end)
    return q to j
}

/** A dotted identifier chain such as `s.t.c`; [index] is the part the offset falls in. */
data class IdentChain(val tokens: List<Token>, val parts: List<String>, val index: Int) {
    val start get() = tokens.first().start
    val end get() = tokens.last().end
}

/** The identifier chain at [offset] (the caret may sit at either edge of a part), or null. */
fun identifierAt(sql: String, offset: Int): IdentChain? {
    val toks = tokenize(sql).filter { it.kind != TokenKind.WHITESPACE }
    var at = toks.indexOfFirst { it.isIdent() && offset >= it.start && offset <= it.end }
    if (at < 0) return null
    var lo = at; var hi = at
    while (lo >= 2 && toks[lo - 1].isPunct(sql, '.') && toks[lo - 2].isIdent() && toks[lo - 1].start == toks[lo - 2].end && toks[lo].start == toks[lo - 1].end) lo -= 2
    while (hi + 2 < toks.size + 0 && toks[hi + 1].isPunct(sql, '.') && toks[hi + 2].isIdent() && toks[hi + 1].start == toks[hi].end && toks[hi + 2].start == toks[hi + 1].end) hi += 2
    val chain = (lo..hi step 2).map { toks[it] }
    return IdentChain(chain, chain.map { unquote(it.text(sql)) }, (at - lo) / 2)
}
