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
 */
fun splitStatements(sql: String): List<StatementRange> {
    val out = mutableListOf<StatementRange>()
    var start = 0
    var hasCode = false

    fun flush(semicolonAt: Int?) {
        val endExclusive = semicolonAt ?: sql.length
        if (hasCode) {
            var s = start
            var e = endExclusive
            while (s < e && sql[s].isWhitespace()) s++
            while (e > s && sql[e - 1].isWhitespace()) e--
            out += StatementRange(s, e, semicolonAt?.plus(1) ?: sql.length)
        }
        start = semicolonAt?.plus(1) ?: sql.length
        hasCode = false
    }

    for (t in tokenize(sql)) {
        when {
            t.kind == TokenKind.PUNCT && sql[t.start] == ';' -> flush(t.start)
            t.kind != TokenKind.WHITESPACE && t.kind != TokenKind.COMMENT -> hasCode = true
        }
    }
    flush(null)
    return out
}

/**
 * The statement the caret belongs to: the one containing [offset] (its ';' counts as inside),
 * otherwise the closest one before it, otherwise the first one after.
 */
fun statementAt(sql: String, offset: Int): StatementRange? {
    val all = splitStatements(sql)
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
