package ksqlc.sql

enum class TokenKind { WHITESPACE, COMMENT, KEYWORD, TYPE, IDENT, QUOTED_IDENT, STRING, NUMBER, PARAM, OPERATOR, PUNCT }

/** [start, end) in the source text. Tokens cover the whole input without gaps or overlaps. */
data class Token(val kind: TokenKind, val start: Int, val end: Int) {
    fun text(source: String) = source.substring(start, end)
}

/**
 * Splits SQL into tokens for highlighting and statement splitting. It never fails: unterminated
 * strings and comments run to the end of the input. Recognises '...' (with '' and \ escapes),
 * "..." and `...` identifiers, -- and /* */ comments, $tag$...$tag$ bodies and $1 parameters.
 */
fun tokenize(sql: String): List<Token> {
    val out = ArrayList<Token>()
    val n = sql.length
    var i = 0
    fun add(kind: TokenKind, from: Int, to: Int) { out += Token(kind, from, to) }

    while (i < n) {
        val c = sql[i]
        val start = i
        when {
            c.isWhitespace() -> {
                while (i < n && sql[i].isWhitespace()) i++
                add(TokenKind.WHITESPACE, start, i)
            }
            c == '-' && sql.startsWith("--", i) -> {
                i = sql.indexOf('\n', i).let { if (it < 0) n else it }
                add(TokenKind.COMMENT, start, i)
            }
            c == '/' && sql.startsWith("/*", i) -> {
                i = sql.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                add(TokenKind.COMMENT, start, i)
            }
            c == '\'' -> { i = skipQuoted(sql, i, c); add(TokenKind.STRING, start, i) }
            c == '"' || c == '`' -> { i = skipQuoted(sql, i, c); add(TokenKind.QUOTED_IDENT, start, i) }
            c == '$' -> {
                val tag = dollarTag(sql, i)
                when {
                    tag != null -> {
                        val close = sql.indexOf(tag, i + tag.length)
                        i = if (close < 0) n else close + tag.length
                        add(TokenKind.STRING, start, i)
                    }
                    i + 1 < n && sql[i + 1].isDigit() -> {
                        i++
                        while (i < n && sql[i].isDigit()) i++
                        add(TokenKind.PARAM, start, i)
                    }
                    else -> { i++; add(TokenKind.OPERATOR, start, i) }
                }
            }
            c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit()) -> { i = skipNumber(sql, i); add(TokenKind.NUMBER, start, i) }
            c.isLetter() || c == '_' -> {
                while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                val word = sql.substring(start, i).uppercase()
                add(if (word in KEYWORDS) TokenKind.KEYWORD else if (word in TYPES) TokenKind.TYPE else TokenKind.IDENT, start, i)
            }
            c in PUNCT_CHARS -> { i++; add(TokenKind.PUNCT, start, i) }
            else -> {
                // A run of operator characters, stopping where a comment would begin.
                i++
                while (i < n && sql[i] !in PUNCT_CHARS && !sql[i].isWhitespace() && !sql[i].isLetterOrDigit() &&
                    sql[i] !in "'\"`\$_" && !sql.startsWith("--", i) && !sql.startsWith("/*", i)
                ) i++
                add(TokenKind.OPERATOR, start, i)
            }
        }
    }
    return out
}

private const val PUNCT_CHARS = "(),;.[]{}"

private fun skipQuoted(sql: String, from: Int, q: Char): Int {
    var i = from + 1
    while (i < sql.length) {
        if (sql[i] == q) {
            if (i + 1 < sql.length && sql[i + 1] == q) { i += 2; continue }  // '' escape
            return i + 1
        }
        if (sql[i] == '\\' && q == '\'' && i + 1 < sql.length) { i += 2; continue }
        i++
    }
    return sql.length
}

private fun skipNumber(sql: String, from: Int): Int {
    var i = from
    val n = sql.length
    if (sql.startsWith("0x", i) || sql.startsWith("0X", i)) {
        i += 2
        while (i < n && (sql[i].isDigit() || sql[i].lowercaseChar() in 'a'..'f')) i++
        return i
    }
    while (i < n && sql[i].isDigit()) i++
    if (i < n && sql[i] == '.' && i + 1 < n && sql[i + 1].isDigit()) {
        i++
        while (i < n && sql[i].isDigit()) i++
    } else if (i < n && sql[i] == '.' && (i + 1 >= n || !sql[i + 1].isLetter())) {
        i++   // "1." is a number; "1.foo" is not
    }
    if (i < n && (sql[i] == 'e' || sql[i] == 'E')) {
        var j = i + 1
        if (j < n && (sql[j] == '+' || sql[j] == '-')) j++
        if (j < n && sql[j].isDigit()) {
            while (j < n && sql[j].isDigit()) j++
            i = j
        }
    }
    return i
}

/** "$$" or "$tag$" starting at [i], else null. */
private fun dollarTag(sql: String, i: Int): String? {
    var j = i + 1
    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
    if (j >= sql.length || sql[j] != '$') return null
    if (j > i + 1 && sql[i + 1].isDigit()) return null  // $1 is a parameter, not a tag
    return sql.substring(i, j + 1)
}

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ROW", "ONLY",
    "UNION", "ALL", "INTERSECT", "EXCEPT", "DISTINCT", "AS", "ON", "USING", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
    "NATURAL", "LATERAL", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
    "TABLE", "VIEW", "MATERIALIZED", "INDEX", "UNIQUE", "SEQUENCE", "SCHEMA", "DATABASE", "FUNCTION", "PROCEDURE", "TRIGGER", "EXTENSION",
    "TEMPORARY", "TEMP", "IF", "EXISTS", "NOT", "NULL", "AND", "OR", "IN", "IS", "LIKE", "ILIKE", "GLOB", "REGEXP", "BETWEEN", "CASE", "WHEN",
    "THEN", "ELSE", "END", "CAST", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "CHECK", "DEFAULT", "CASCADE", "RESTRICT", "ADD",
    "COLUMN", "WITH", "RECURSIVE", "RETURNING", "RETURNS", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION", "START", "GRANT",
    "REVOKE", "TO", "EXPLAIN", "ANALYZE", "VACUUM", "OVER", "PARTITION", "WINDOW", "ASC", "DESC", "NULLS", "LAST", "TRUE", "FALSE", "ANY",
    "SOME", "ESCAPE", "COLLATE", "INTERVAL", "FOR", "REPLACE", "LANGUAGE", "DECLARE", "USE", "SHOW", "DESCRIBE", "PRAGMA", "AUTOINCREMENT",
    "AUTO_INCREMENT", "UNSIGNED", "ENGINE", "CONFLICT", "DO", "NOTHING", "UNLOGGED", "VIRTUAL", "GENERATED", "ALWAYS", "STORED", "IDENTITY",
)

private val TYPES = setOf(
    "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "SERIAL", "BIGSERIAL", "SMALLSERIAL", "VARCHAR", "CHAR", "CHARACTER",
    "NVARCHAR", "NCHAR", "TEXT", "CLOB", "BOOLEAN", "BOOL", "BIT", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "DATETIME", "NUMERIC", "DECIMAL",
    "MONEY", "FLOAT", "DOUBLE", "REAL", "UUID", "JSON", "JSONB", "XML", "BLOB", "BYTEA", "BINARY", "VARBINARY",
)
