package ksqlc.sql

/** A statement's text range in the source: [start, end) excludes the terminating ';', [next] includes it. */
data class StatementRange(val start: Int, val end: Int, val next: Int) {
    fun text(source: String) = source.substring(start, end)

    /** Start of the first token that is neither whitespace nor a comment, so leading comments stay out of an outline. */
    fun codeStart(source: String): Int {
        val t = tokenize(source.substring(start, end)).firstOrNull { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT }
        return start + (t?.start ?: 0)
    }
}

/**
 * Splits [sql] on top-level semicolons. Semicolons inside strings, quoted identifiers,
 * comments and $tag$...$tag$ bodies do not split (see [tokenize]).
 * Ranges are trimmed of surrounding whitespace; empty and comment-only statements are dropped.
 *
 * With [slashBlocks] (Oracle) a PL/SQL block (`begin`, `declare`, or `create [or replace] procedure|function|package|trigger|type`)
 * is not split at its inner semicolons: it ends at a line holding only `/`, which is not part of the statement.
 *
 * With [goBatches] (SQL Server) a line holding only `GO` ends the statement before it, and is not part of any statement.
 */
fun splitStatements(sql: String, slashBlocks: Boolean = false, goBatches: Boolean = false): List<StatementRange> {
    val out = mutableListOf<StatementRange>()
    var start = 0
    var hasCode = false

    fun flush(semicolonAt: Int?, resumeAt: Int? = semicolonAt?.plus(1)) {
        val endExclusive = semicolonAt ?: sql.length
        if (hasCode) {
            var s = start
            var e = endExclusive
            while (s < e && sql[s].isWhitespace()) s++
            while (e > s && sql[e - 1].isWhitespace()) e--
            out += StatementRange(s, e, resumeAt ?: sql.length)
        }
        start = resumeAt ?: sql.length
        hasCode = false
    }

    val all = tokenize(sql)
    var block = false
    for ((idx, t) in all.withIndex()) {
        val code = t.kind != TokenKind.WHITESPACE && t.kind != TokenKind.COMMENT
        if (slashBlocks && code && !hasCode) block = startsPlsqlBlock(sql, all, idx)
        when {
            block && t.kind == TokenKind.OPERATOR && sql[t.start] == '/' && t.end - t.start == 1 && aloneOnItsLine(sql, t) -> { flush(t.start); block = false }
            block -> if (code) hasCode = true
            goBatches && code && t.end - t.start == 2 && sql.substring(t.start, t.end).equals("go", true) && aloneOnItsLine(sql, t) -> flush(t.start, t.end)
            t.kind == TokenKind.PUNCT && sql[t.start] == ';' -> flush(t.start)
            code -> hasCode = true
        }
    }
    flush(null)
    return out
}

private val BLOCK_OBJECTS = setOf("PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE")

/** Does the statement whose first code token is at [from] open a PL/SQL block? */
private fun startsPlsqlBlock(sql: String, tokens: List<Token>, from: Int): Boolean {
    val words = tokens.drop(from).filter { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT }.take(6).map { it.text(sql).uppercase() }
    return when (words.firstOrNull()) {
        "BEGIN", "DECLARE" -> true
        "CREATE" -> words.drop(1).filterNot { it in setOf("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE") }.firstOrNull() in BLOCK_OBJECTS
        else -> false
    }
}

private fun aloneOnItsLine(sql: String, t: Token): Boolean {
    val before = sql.lastIndexOf('\n', t.start - 1).let { sql.substring(it + 1, t.start) }
    val after = sql.indexOf('\n', t.end).let { if (it < 0) sql.substring(t.end) else sql.substring(t.end, it) }
    return before.isBlank() && after.isBlank()
}

/**
 * The statement the caret belongs to: the one containing [offset] (its ';' counts as inside),
 * otherwise the closest one before it, otherwise the first one after.
 */
fun statementAt(sql: String, offset: Int, slashBlocks: Boolean = false, goBatches: Boolean = false): StatementRange? {
    val all = splitStatements(sql, slashBlocks, goBatches)
    all.firstOrNull { offset >= it.start && offset <= it.next }?.let { return it }
    return all.lastOrNull { it.next <= offset } ?: all.firstOrNull()
}

/** First keyword of [sql], upper-cased, skipping leading whitespace and comments; null if there is none. */
fun firstKeyword(sql: String): String? {
    val t = tokenize(sql).firstOrNull { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT } ?: return null
    return if (t.kind == TokenKind.KEYWORD || t.kind == TokenKind.TYPE || t.kind == TokenKind.IDENT) t.text(sql).uppercase() else null
}

private val SCHEMA_CHANGING = setOf("CREATE", "ALTER", "DROP", "RENAME")

/** True for DDL that can add, remove or rename objects, so a schema tree should be refreshed afterwards. */
fun changesSchema(sql: String): Boolean = firstKeyword(sql) in SCHEMA_CHANGING
