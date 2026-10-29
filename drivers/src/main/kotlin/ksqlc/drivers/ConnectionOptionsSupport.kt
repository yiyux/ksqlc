package ksqlc.drivers

import ksqlc.core.tr

import ksqlc.core.ConnectionOptions
import ksqlc.core.DbKind
import ksqlc.sql.splitStatements

/** Engines with a session time zone setting we can issue. */
fun supportsTimeZone(kind: DbKind) = kind == DbKind.POSTGRES || kind == DbKind.MYSQL || kind == DbKind.MARIADB || kind == DbKind.ORACLE

/** Engines where "single database" narrows the tree: MySQL-family and Cassandra list every database or keyspace of the server, Oracle every user. */
fun supportsSingleDatabase(kind: DbKind) = kind == DbKind.MYSQL || kind == DbKind.MARIADB || kind == DbKind.CASSANDRA || kind == DbKind.ORACLE

fun supportsTransactions(kind: DbKind) = kind != DbKind.CASSANDRA && kind != DbKind.TRINO

private fun String.sqlQuoted() = "'" + replace("'", "''") + "'"

/**
 * What a new connection runs before anything else: the time zone, the user's startup script (split like the console splits a
 * script), and last, for the engines that can refuse writes for the whole session, read-only mode. It goes last so the script
 * can still prepare the session (a temporary table, a variable); after it not even a statement the text check lets through can write.
 */
fun sessionStartup(kind: DbKind, options: ConnectionOptions): List<String> = buildList {
    val tz = options.timeZone.trim()
    if (tz.isNotEmpty() && supportsTimeZone(kind)) add(when (kind) {
        DbKind.POSTGRES -> "set time zone ${tz.sqlQuoted()}"
        DbKind.ORACLE -> "alter session set time_zone = ${tz.sqlQuoted()}"
        else -> "set time_zone = ${tz.sqlQuoted()}"
    })
    val script = options.startupScript
    splitStatements(script, slashBlocks = kind == DbKind.ORACLE, goBatches = kind == DbKind.MSSQL).forEach { add(it.text(script)) }
    if (options.readOnly) when (kind) {
        DbKind.POSTGRES -> add("set default_transaction_read_only = on")
        DbKind.MYSQL, DbKind.MARIADB -> add("set session transaction read only")
        DbKind.SQLITE -> add("pragma query_only = on")
        else -> {}
    }
}

/** A statement that only reads. WITH counts unless it writes (see [readOnlyViolation]). */
private val READS = setOf("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE", "USE", "LIST")
private val WRITES_IN_WITH = setOf("INSERT", "UPDATE", "DELETE", "MERGE")

/**
 * Why [sql] may not run on a read-only connection, or null when it may. A WITH is a read unless it writes (PostgreSQL allows
 * `WITH x AS (INSERT … RETURNING …) SELECT …`). This is a check on the text; engines that can enforce read-only themselves also do (see [sessionStartup]).
 */
fun readOnlyViolation(sql: String): String? {
    val first = ksqlc.sql.firstKeyword(sql)
    val writes = first == "WITH" && ksqlc.sql.tokenize(sql).any { it.kind == ksqlc.sql.TokenKind.KEYWORD && it.text(sql).uppercase() in WRITES_IN_WITH }
    return if (first in READS && !writes) null
    else tr("La conexión es de solo lectura: solo se permiten consultas (SELECT, WITH, SHOW, EXPLAIN…)")
}

/** A query the engine answers trivially, to keep an idle connection alive. */
fun keepAliveSql(kind: DbKind) = when (kind) {
    DbKind.ORACLE -> "select 1 from dual"
    DbKind.CASSANDRA -> "select release_version from system.local"
    else -> "select 1"
}
