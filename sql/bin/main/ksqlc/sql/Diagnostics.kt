package ksqlc.sql

enum class Severity { ERROR, WARNING }
data class Diagnostic(val start: Int, val end: Int, val severity: Severity, val message: String)

private val SYSTEM_SCHEMAS = setOf("information_schema", "pg_catalog", "sys", "mysql", "performance_schema", "pg_temp")
private val BUILTIN_NAMES = setOf("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "LOCALTIME", "LOCALTIMESTAMP", "SESSION_USER", "USER", "ROWID", "OID", "CTID")

/**
 * Problems worth underlining. Errors are certain (unterminated quotes/comments, unbalanced parentheses);
 * warnings are only raised when [schema] can prove them, so a quiet editor never means a correct query.
 */
fun diagnose(sql: String, schema: SchemaInfo?): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    val all = tokenize(sql)

    // syntax
    val open = ArrayList<Token>()
    for (t in all) {
        when {
            (t.kind == TokenKind.STRING || t.kind == TokenKind.QUOTED_IDENT || t.kind == TokenKind.COMMENT) && !isTerminated(sql, t) ->
                out += Diagnostic(t.start, minOf(t.end, t.start + 1).coerceAtLeast(t.start + 1).coerceAtMost(sql.length), Severity.ERROR,
                    when (t.kind) { TokenKind.COMMENT -> "Comentario sin cerrar"; TokenKind.QUOTED_IDENT -> "Identificador entrecomillado sin cerrar"; else -> "Cadena sin cerrar" })
            t.isPunct(sql, '(') -> open += t
            t.isPunct(sql, ')') -> if (open.isEmpty()) out += Diagnostic(t.start, t.end, Severity.ERROR, "Paréntesis de cierre sin apertura") else open.removeLast()
            t.isPunct(sql, ';') -> { open.forEach { out += Diagnostic(it.start, it.end, Severity.ERROR, "Paréntesis sin cerrar") }; open.clear() }
        }
    }
    open.forEach { out += Diagnostic(it.start, it.end, Severity.ERROR, "Paréntesis sin cerrar") }

    if (schema != null) {
        val created = createdNames(sql, all)
        for (r in splitStatements(sql)) schemaWarnings(sql, r, schema, created, out)
    }
    return out.sortedBy { it.start }
}

/** Names this text itself creates (`create table x`), which the catalog cannot know yet. */
private fun createdNames(sql: String, all: List<Token>): Set<String> {
    val t = all.filter { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT }
    val names = HashSet<String>()
    for (i in t.indices) {
        if (t[i].upper(sql) != "CREATE") continue
        var j = i + 1
        while (j < t.size && t[j].kind == TokenKind.KEYWORD && t[j].upper(sql) in setOf("OR", "REPLACE", "TEMP", "TEMPORARY", "UNIQUE", "MATERIALIZED", "UNLOGGED", "VIRTUAL")) j++
        if (j >= t.size || t[j].upper(sql) !in setOf("TABLE", "VIEW")) continue
        j++
        while (j < t.size && t[j].upper(sql) in setOf("IF", "NOT", "EXISTS")) j++
        var last: Token? = null
        while (j < t.size && t[j].isIdent()) { last = t[j]; j++; if (j + 1 < t.size && t[j].isPunct(sql, '.')) j++ else break }
        last?.let { names += unquote(it.text(sql)).lowercase() }
    }
    return names
}

private fun schemaWarnings(sql: String, r: StatementRange, schema: SchemaInfo, created: Set<String>, out: MutableList<Diagnostic>) {
    val refs = tableRefs(sql, r)
    val real = refs.filter { !it.isCte }
    val cteNames = refs.filter { it.isCte }.map { it.name.lowercase() }.toSet()
    val resolved = HashMap<QueryTable, TableRef?>()

    if (schema.complete) {
        for (q in real) {
            val known = q.name.lowercase() in cteNames || q.name.lowercase() in created ||
                (q.schema?.lowercase() in SYSTEM_SCHEMAS) || q.name.lowercase().let { it.startsWith("sqlite_") || it.startsWith("pg_") }
            val hit = schema.find(q.schema, q.name)
            resolved[q] = hit
            if (hit == null && !known) out += Diagnostic(q.start, q.end, Severity.WARNING, "Tabla desconocida: ${q.schema?.let { "$it." } ?: ""}${q.name}")
        }
    } else real.forEach { resolved[it] = schema.find(it.schema, it.name) }

    val toks = significant(sql, r)
    val tableTokens = real.flatMap { q -> toks.filter { it.start >= q.start && it.end <= q.end } }.toSet()

    // alias.column against a table whose columns are loaded
    for (i in 0 until toks.size - 2) {
        val a = toks[i]; val dot = toks[i + 1]; val c = toks[i + 2]
        if (!a.isIdent() || !dot.isPunct(sql, '.') || !c.isIdent() || a in tableTokens) continue
        if (i > 0 && toks[i - 1].isPunct(sql, '.')) continue
        if (i + 3 < toks.size && toks[i + 3].isPunct(sql, '.') ) continue
        if (i + 3 < toks.size && toks[i + 3].isPunct(sql, '(')) continue
        val q = unquote(a.text(sql))
        val ref = real.firstOrNull { it.answersTo(q) } ?: continue
        val cols = resolved[ref]?.let { schema.columns(it) } ?: continue
        val name = unquote(c.text(sql))
        if (cols.none { it.name.equals(name, true) }) out += Diagnostic(c.start, c.end, Severity.WARNING, "Columna desconocida: ${ref.alias ?: ref.name}.$name")
    }

    // bare names in WHERE, when the statement reads from exactly one known table
    if (real.size == 1 && refs.none { it.isCte } ) {
        val only = real[0]
        val cols = resolved[only]?.let { schema.columns(it) } ?: return
        val aliasDefs = toks.indices.filter { i -> i > 0 && toks[i - 1].upper(sql) == "AS" && toks[i].isIdent() }.map { unquote(toks[it].text(sql)).lowercase() }.toSet()
        var inWhere = false
        var depth = 0
        for (i in toks.indices) {
            val t = toks[i]
            if (t.isPunct(sql, '(')) depth++
            if (t.isPunct(sql, ')')) depth--
            if (t.kind == TokenKind.KEYWORD) {
                val w = t.upper(sql)
                if (w == "WHERE") inWhere = true
                else if (w in setOf("GROUP", "ORDER", "HAVING", "LIMIT", "UNION", "RETURNING", "INTERSECT", "EXCEPT", "WINDOW", "OFFSET")) inWhere = false
                if (w == "SELECT" && depth > 0) return   // a subquery brings its own tables
            }
            if (!inWhere || t.kind != TokenKind.IDENT || t in tableTokens) continue
            if (i > 0 && toks[i - 1].isPunct(sql, '.')) continue
            if (i + 1 < toks.size && (toks[i + 1].isPunct(sql, '(') || toks[i + 1].isPunct(sql, '.'))) continue
            val name = t.text(sql)
            if (name.uppercase() in BUILTIN_NAMES || name.lowercase() in aliasDefs || only.answersTo(name)) continue
            if (cols.none { it.name.equals(name, true) }) out += Diagnostic(t.start, t.end, Severity.WARNING, "Columna desconocida: $name")
        }
    }
}
