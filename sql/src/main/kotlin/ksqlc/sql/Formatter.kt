package ksqlc.sql

private const val INDENT = 4

private val CLAUSE_STARTERS = setOf("SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "VALUES", "RETURNING", "SET", "UNION", "INTERSECT", "EXCEPT")
private val JOIN_MODIFIERS = setOf("INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL")
private val FUNCTION_KEYWORDS = setOf("CAST", "REPLACE", "LEFT", "RIGHT", "IF", "CHAR")
private val LIST_ENDERS = setOf("FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION", "INTERSECT", "EXCEPT", "RETURNING", "INTO", "OFFSET")

private enum class Kind { TOP, SUB, PAREN }
private class Frame(val kind: Kind, val indent: Int, val parentLine: Int) {
    var clause: String? = null
    var list = false
    var pendingBreak = false
    var between = false
    var cases = 0
}

/**
 * Reformats [sql]: keywords upper-cased, one clause per line, multi-item SELECT/SET lists one item per line,
 * AND/OR of WHERE/HAVING/ON on their own lines, sub-selects indented. Comments, strings and quoted names are
 * kept as written, and formatting the result again changes nothing.
 */
fun formatSql(sql: String): String {
    val all = tokenize(sql)
    val sig = ArrayList<Token>()
    val newlineBefore = HashMap<Int, Boolean>()   // index in sig -> a line break separated it from the previous token
    var nl = false
    for (t in all) {
        if (t.kind == TokenKind.WHITESPACE) { if ('\n' in sql.substring(t.start, t.end)) nl = true; continue }
        newlineBefore[sig.size] = nl; nl = false; sig += t
    }
    if (sig.isEmpty()) return sql
    fun text(i: Int) = sig[i].text(sql)
    fun up(i: Int) = if (i in sig.indices && sig[i].kind == TokenKind.KEYWORD) text(i).uppercase() else ""
    fun punct(i: Int, c: Char) = i in sig.indices && sig[i].isPunct(sql, c)

    /** Does the list that starts after index [i] (a SELECT or SET) have a top-level comma? */
    fun multiItem(i: Int): Boolean {
        var depth = 0; var j = i + 1
        while (j < sig.size) {
            when {
                punct(j, '(') -> depth++
                punct(j, ')') -> { if (depth == 0) return false; depth-- }
                punct(j, ';') -> return false
                depth == 0 && punct(j, ',') -> return true
                depth == 0 && up(j) in LIST_ENDERS -> return false
            }
            j++
        }
        return false
    }

    /** A keyword used as a name (after a dot, before one, or where a table or alias goes) keeps its case. */
    fun isNameKeyword(i: Int) = punct(i - 1, '.') || punct(i + 1, '.') || (i > 0 && sig[i - 1].kind == TokenKind.KEYWORD && text(i - 1).uppercase() in setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "AS"))

    val out = StringBuilder()
    val frames = ArrayList<Frame>().apply { add(Frame(Kind.TOP, 0, 0)) }
    var lineIndent = 0
    fun newline(indent: Int, blank: Boolean = false) {
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        if (out.isEmpty()) { lineIndent = indent; out.append(" ".repeat(indent)); return }
        if (out.last() != '\n') out.append('\n')
        if (blank && !out.endsWith("\n\n")) out.append('\n')
        out.append(" ".repeat(indent)); lineIndent = indent
    }
    fun fresh() = out.isEmpty() || out.substring(out.lastIndexOf('\n') + 1).isBlank()

    var prev = -1            // previous significant token index that was emitted
    var stmtStart = true
    var statementEnded = false
    var pendingLineBreak = false

    for (i in sig.indices) {
        val tok = sig[i]
        val frame = frames.last()
        val raw = text(i)
        val word = if (tok.kind == TokenKind.KEYWORD) raw.uppercase() else ""
        val isComment = tok.kind == TokenKind.COMMENT

        // --- placement: decide whether this token starts a new line ----------------------------
        if (isComment) {
            val own = newlineBefore[i] == true || prev < 0
            when {
                statementEnded && !own -> out.append(' ')                       // trailing comment after ';'
                statementEnded -> { newline(0, blank = true); statementEnded = false }
                own -> newline(frame.indent + if (frame.list || frame.pendingBreak) INDENT else 0)
                !fresh() -> out.append(' ')
            }
            out.append(raw)
            prev = i
            pendingLineBreak = raw.startsWith("--") || newlineBefore[i + 1] == true
            continue
        }
        if (statementEnded) { newline(0, blank = true); statementEnded = false; stmtStart = true }
        if (pendingLineBreak) { newline(if (frame.pendingBreak) frame.indent + INDENT else lineIndent); pendingLineBreak = false }
        var breakIndent: Int? = null
        val nextIsParen = punct(i + 1, '(')
        if (frame.kind != Kind.PAREN && !stmtStart) {
            val clause = word in CLAUSE_STARTERS && !(word == "FROM" && (up(prev) == "DELETE" || up(prev) == "DISTINCT"))
            val groupOrder = (word == "GROUP" || word == "ORDER") && up(i + 1) == "BY"
            val join = when {
                word == "JOIN" -> up(prev) !in JOIN_MODIFIERS
                word in JOIN_MODIFIERS && !nextIsParen -> up(prev) !in JOIN_MODIFIERS
                else -> false
            }
            val joinWord = (word == "LEFT" || word == "RIGHT") && nextIsParen
            val logic = (word == "AND" || word == "OR") && frame.cases == 0 && frame.clause in setOf("WHERE", "HAVING", "ON") && !(word == "AND" && frame.between)
            when {
                joinWord -> {}
                clause -> breakIndent = frame.indent
                groupOrder || join -> breakIndent = frame.indent
                logic -> breakIndent = frame.indent + INDENT
                word == "ON" -> {}
                frame.list && prev >= 0 && punct(prev, ',') -> breakIndent = frame.indent + INDENT
            }
        }
        if (frame.pendingBreak && breakIndent == null && !(word == "DISTINCT" || word == "ALL" && up(prev) == "SELECT")) {
            breakIndent = frame.indent + INDENT; frame.pendingBreak = false
        }

        // --- spacing ---------------------------------------------------------------------------
        val space: Boolean = when {
            breakIndent != null -> { newline(breakIndent); false }
            fresh() -> false
            prev < 0 -> false
            else -> {
                val p = sig[prev]; val pt = text(prev)
                when {
                    punct(i, ')') || punct(i, ']') || punct(i, ',') || punct(i, ';') || punct(i, '.') -> false
                    p.isPunct(sql, '(') || p.isPunct(sql, '[') || p.isPunct(sql, '.') -> false
                    punct(i, '(') -> !(p.kind == TokenKind.IDENT || p.kind == TokenKind.QUOTED_IDENT || p.kind == TokenKind.TYPE || (p.kind == TokenKind.KEYWORD && pt.uppercase() in FUNCTION_KEYWORDS) || p.kind == TokenKind.PARAM)
                    punct(i, '[') -> false
                    tok.kind == TokenKind.OPERATOR && (raw == "::") -> false
                    p.kind == TokenKind.OPERATOR && (pt == "::" || pt == ":" || pt == "@") -> false
                    tok.kind == TokenKind.OPERATOR && raw == ":" -> !(p.kind == TokenKind.OPERATOR)
                    p.kind == TokenKind.OPERATOR && (pt == "-" || pt == "+") && prev > 0 && (sig[prev - 1].kind == TokenKind.OPERATOR || sig[prev - 1].isPunct(sql, '(') || sig[prev - 1].isPunct(sql, ',') || (sig[prev - 1].kind == TokenKind.KEYWORD && up(prev - 1) !in setOf("END", "NULL", "TRUE", "FALSE")) ) -> false
                    else -> true
                }
            }
        }
        if (space) out.append(' ')

        // --- emit ------------------------------------------------------------------------------
        when {
            tok.kind == TokenKind.KEYWORD && !isNameKeyword(i) -> out.append(word)
            else -> out.append(raw)
        }

        // --- state updates ---------------------------------------------------------------------
        stmtStart = false
        when {
            punct(i, '(') -> {
                val sub = up(i + 1) == "SELECT" || up(i + 1) == "WITH"
                if (sub) {
                    val f = Frame(Kind.SUB, lineIndent + INDENT, lineIndent)
                    frames += f
                    newline(f.indent)
                } else frames += Frame(Kind.PAREN, frame.indent, lineIndent)
            }
            punct(i, ')') -> if (frames.size > 1) {
                val f = frames.removeLast()
                if (f.kind == Kind.SUB) {
                    // put the closing paren on its own line, then re-add it
                    out.setLength(out.length - 1)
                    newline(f.parentLine); out.append(')')
                }
            }
            punct(i, ';') -> { while (frames.size > 1) frames.removeLast(); frames.last().apply { clause = null; list = false; pendingBreak = false; between = false; cases = 0 }; statementEnded = true; stmtStart = true }
            tok.kind == TokenKind.KEYWORD -> {
                if (frame.kind != Kind.PAREN) {
                    when (word) {
                        "SELECT", "SET" -> {
                            frame.clause = word
                            frame.list = multiItem(i)
                            frame.pendingBreak = frame.list
                        }
                        "FROM", "WHERE", "HAVING", "ON", "GROUP", "ORDER", "LIMIT", "OFFSET", "RETURNING", "VALUES", "UNION", "INTERSECT", "EXCEPT", "JOIN" -> {
                            if (!(word == "FROM" && up(i - 1) == "DISTINCT")) { frame.clause = word; frame.list = false; frame.pendingBreak = false }
                        }
                    }
                }
                when (word) {
                    "BETWEEN" -> frame.between = true
                    "AND" -> frame.between = false
                    "CASE" -> frame.cases++
                    "END" -> if (frame.cases > 0) frame.cases--
                }
            }
        }
        prev = i
    }
    var result = out.toString().trimEnd()
    if (sql.endsWith("\n")) result += "\n"
    return result
}
