package ksqlc.sql

enum class Slot { TABLE, COLUMN, KEYWORD }

/**
 * What is being typed at the caret: the [prefix] already written (it starts at [replaceStart]), the dotted
 * [qualifier] in front of it (`a.` → ["a"]), and the kind of name the grammar expects there.
 */
data class CompletionContext(val prefix: String, val replaceStart: Int, val qualifier: List<String>, val slot: Slot)

enum class SuggestionKind { TABLE, VIEW, COLUMN, SCHEMA, KEYWORD }
data class Suggestion(val text: String, val kind: SuggestionKind, val detail: String? = null)

private val TABLE_INTRO = setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE")
private val CLAUSES = setOf("SELECT", "FROM", "JOIN", "WHERE", "ON", "GROUP", "ORDER", "HAVING", "SET", "UPDATE", "INTO", "VALUES", "LIMIT", "USING", "TABLE")
private val COLUMN_CLAUSES = setOf("SELECT", "WHERE", "ON", "GROUP", "ORDER", "HAVING", "SET", "USING")

/** Nearest clause keyword before the end of [t] at the caret's nesting level (looking out of enclosing parentheses). */
private fun clauseOf(sql: String, t: List<Token>): String? {
    var depth = 0
    for (i in t.indices.reversed()) {
        val tok = t[i]
        when {
            tok.isPunct(sql, ')') -> depth++
            tok.isPunct(sql, '(') -> if (depth > 0) depth-- 
            depth == 0 && tok.kind == TokenKind.KEYWORD && tok.upper(sql) in CLAUSES -> return tok.upper(sql)
        }
    }
    return null
}

/** The completion context at [caret], or null inside strings and comments. */
fun completionContext(sql: String, caret: Int): CompletionContext? {
    val c = caret.coerceIn(0, sql.length)
    val all = tokenize(sql)
    all.firstOrNull { c > it.start && c < it.end && (it.kind == TokenKind.STRING || it.kind == TokenKind.COMMENT) }?.let { return null }
    all.firstOrNull { c == it.end && it.kind == TokenKind.COMMENT && it.text(sql).startsWith("--") }?.let { return null }
    var p = c
    while (p > 0 && (sql[p - 1].isLetterOrDigit() || sql[p - 1] == '_')) p--
    val prefix = sql.substring(p, c)
    // qualifier chain: ident '.' ident '.' ... directly before the prefix
    val qualifier = ArrayList<String>()
    var q = p
    while (q > 0 && sql[q - 1] == '.') {
        val identEnd = q - 1
        var s = identEnd
        if (s > 0 && (sql[s - 1] == '"' || sql[s - 1] == '`')) {
            val quote = sql[s - 1]; s--
            s = sql.lastIndexOf(quote, s - 1).takeIf { it >= 0 } ?: break
            qualifier.add(0, unquote(sql.substring(s, identEnd)))
        } else {
            while (s > 0 && (sql[s - 1].isLetterOrDigit() || sql[s - 1] == '_')) s--
            if (s == identEnd) break
            qualifier.add(0, sql.substring(s, identEnd))
        }
        q = s
    }
    val stmtStart = all.lastOrNull { it.isPunct(sql, ';') && it.end <= q }?.end ?: 0
    val before = if (stmtStart <= q) significant(sql, StatementRange(stmtStart, q, q)) else emptyList()
    val last = before.lastOrNull()
    val clause = clauseOf(sql, before)
    val slot = when {
        qualifier.isNotEmpty() -> if (clause in setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE")) Slot.TABLE else Slot.COLUMN
        last == null -> Slot.KEYWORD
        last.kind == TokenKind.KEYWORD && last.upper(sql) in TABLE_INTRO -> Slot.TABLE
        last.isPunct(sql, ',') && clause == "FROM" -> Slot.TABLE
        (last.kind == TokenKind.KEYWORD || last.kind == TokenKind.OPERATOR || last.isPunct(sql, ',') || last.isPunct(sql, '(')) && clause in COLUMN_CLAUSES -> Slot.COLUMN
        else -> Slot.KEYWORD
    }
    return CompletionContext(prefix, p, qualifier, slot)
}

private fun resolve(schema: SchemaInfo, r: QueryTable): TableRef? = if (r.isCte) null else schema.find(r.schema, r.name)

/** Candidates for [ctx], best first: names starting with the prefix, then names merely containing it. */
fun suggest(ctx: CompletionContext, schema: SchemaInfo, refs: List<QueryTable>, limit: Int = 50): List<Suggestion> {
    val pool = ArrayList<Suggestion>()
    fun tables(restrict: String?) = schema.tables().filter { restrict == null || it.schema.equals(restrict, true) }
        .forEach { pool += Suggestion(it.name, if (it.isView) SuggestionKind.VIEW else SuggestionKind.TABLE, if (restrict == null) it.schema else null) }
    fun columns(t: TableRef?) = t?.let { schema.columns(it) }?.forEach { pool += Suggestion(it.name, SuggestionKind.COLUMN, it.type) }
    fun keywords() = SQL_KEYWORDS.sorted().forEach { pool += Suggestion(it, SuggestionKind.KEYWORD) }

    val qual = ctx.qualifier
    when {
        qual.size == 1 -> {
            val q = qual[0]
            val byRef = refs.firstOrNull { it.answersTo(q) }
            val inSchema = schema.schemas().any { it.equals(q, true) }
            when {
                ctx.slot == Slot.TABLE && inSchema -> tables(q)
                byRef != null -> columns(resolve(schema, byRef))
                inSchema -> tables(q)
                else -> columns(schema.find(null, q))
            }
        }
        qual.size >= 2 -> columns(schema.find(qual[qual.size - 2], qual.last()))
        ctx.slot == Slot.TABLE -> { tables(null); schema.schemas().forEach { pool += Suggestion(it, SuggestionKind.SCHEMA) } }
        ctx.slot == Slot.COLUMN -> {
            val resolved = refs.mapNotNull { resolve(schema, it) }.distinct()
            resolved.forEach { columns(it) }
            keywords()
        }
        else -> keywords()
    }
    val p = ctx.prefix
    val starts = pool.filter { it.text.startsWith(p, true) && !(it.kind == SuggestionKind.KEYWORD && it.text.equals(p, true) && p.isNotEmpty()) }
    val contains = if (p.isEmpty()) emptyList() else pool.filter { !it.text.startsWith(p, true) && it.text.contains(p, true) && it.kind != SuggestionKind.KEYWORD }
    return (starts + contains).distinctBy { it.kind to it.text }.take(limit)
}
