package ksqlc.drivers

import ksqlc.core.*
import ksqlc.sql.firstKeyword
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * One live connection, like a console session: transaction state and session settings persist
 * across statements. Statements run one at a time; [cancel] may be called from any thread.
 */
class Session(private val ds: DataSource, private val password: String?, autoCommit: Boolean = !ds.manualTx) : AutoCloseable {
    private var conn: Connection? = null
    // Cassandra and Trino only have auto-commit: no transactions, so no manual mode
    private val noTransactions = !supportsTransactions(ds.kind)
    @Volatile var autoCommit: Boolean = autoCommit || noTransactions
        private set
    /** In manual mode: something that is not a plain read has run since the last commit or rollback. */
    @Volatile var pending: Boolean = false
        private set
    @Volatile private var current: Statement? = null
    @Volatile private var cancelRequested = false
    @Volatile private var activeCursor: JdbcCursor? = null

    private val options = ds.options
    /** When something last used this session (a statement started or finished), for closing it after a while idle. */
    @Volatile var lastUsed: Long = System.currentTimeMillis()
        private set
    private fun touch() { lastUsed = System.currentTimeMillis() }
    /** A statement is running or a result is still open on this connection. */
    val busy: Boolean get() = current != null || activeCursor?.isClosed == false

    @Synchronized private fun connection(): Connection =
        conn?.takeIf { !it.isClosed } ?: openConnection(ds, password).also { c ->
            c.autoCommit = autoCommit
            // drivers differ on whether they accept the flag (SQLite refuses it after connecting): the check on what is sent covers the rest
            if (options.readOnly) runCatching { c.isReadOnly = true }
            try {
                sessionStartup(ds.kind, options).forEach { sql -> c.createStatement().use { it.execute(sql) } }
            } catch (e: SQLException) {
                runCatching { c.close() }
                throw SQLException("Falló el script de inicio: ${e.message}", e.sqlState, e.errorCode, e)
            }
            conn = c
        }

    /** Runs the keep-alive query if the connection is open and nothing else is using it. Returns whether it ran. */
    @Synchronized fun keepAlive(): Boolean {
        val c = conn?.takeIf { !it.isClosed } ?: return false
        if (busy) return false
        return runCatching { c.createStatement().use { it.execute(keepAliveSql(ds.kind)) }; keepAlives++; true }.getOrDefault(false)
    }

    /** How many keep-alive queries have run on this session (for tests and the status line). */
    @Volatile var keepAlives = 0
        private set

    /** Switches between confirming every statement and confirming by hand. Resolve [pending] work first: JDBC commits it when going back to auto. */
    @Synchronized fun setAutoCommit(auto: Boolean) {
        if (noTransactions) return
        autoCommit = auto
        conn?.takeIf { !it.isClosed }?.let { it.autoCommit = auto }
        if (auto) pending = false
    }

    @Synchronized fun commit() { if (!autoCommit) conn?.takeIf { !it.isClosed }?.commit(); pending = false }
    @Synchronized fun rollback() { if (!autoCommit) conn?.takeIf { !it.isClosed }?.rollback(); pending = false }

    /**
     * Runs [statements] with bound parameters as one unit. In auto mode they are confirmed together at the end (all or nothing);
     * in manual mode they join the open transaction, and if one fails the earlier ones are kept (a savepoint is rolled back).
     * Returns the number of affected rows, or a failure whose message names the statement that went wrong.
     */
    @Synchronized fun execute(statements: List<DmlStatement>): Result<Long> {
        if (options.readOnly) return Result.failure(SQLException(readOnlyViolation("insert")))
        touch()
        var current: String? = null
        return transact({ current }) { c ->
            var total = 0L
            for (d in statements) {
                current = d.preview
                c.prepareStatement(d.sql).use { ps -> bindParams(ps, d.params); total += ps.updateCountOfExecution() }
            }
            total
        }
    }

    /**
     * Runs the one statement [sql] once per row of [rows] (its parameters), [batchSize] at a time, with the same
     * transaction rules as [execute]: nothing is kept if any row fails. [rows] may throw (e.g. a value that does not
     * fit its column); that fails the run the same way. Returns the number of rows sent.
     */
    @Synchronized fun executeBatch(sql: String, rows: Iterator<List<Any?>>, batchSize: Int = 500, onProgress: (Long) -> Unit = {}): Result<Long> =
        if (options.readOnly) Result.failure(SQLException(readOnlyViolation("insert"))) else transact({ sql }) { c ->
            var sent = 0L
            c.prepareStatement(sql).use { ps ->
                var inBatch = 0
                val meta = lazy { runCatching { ps.parameterMetaData }.getOrNull() }
                for (row in rows) {
                    bindParams(ps, row, meta); ps.addBatch(); sent++
                    if (++inBatch == batchSize) { ps.executeBatch(); inBatch = 0; onProgress(sent) }
                }
                if (inBatch > 0) ps.executeBatch()
            }
            onProgress(sent)
            sent
        }

    /**
     * SQL Server sends a bare null as nvarchar and then refuses to convert it for a binary column, so a null there is set with the type the
     * statement says that parameter has. [meta] is asked at most once per statement; if the driver cannot say, the null goes as before.
     */
    private fun bindParams(ps: java.sql.PreparedStatement, params: List<Any?>, meta: Lazy<java.sql.ParameterMetaData?> = lazy { runCatching { ps.parameterMetaData }.getOrNull() }) {
        params.forEachIndexed { i, v ->
            when {
                v is UntypedParam -> ps.setObject(i + 1, v.text, java.sql.Types.OTHER)
                v == null && ds.kind == DbKind.MSSQL ->
                    meta.value?.let { m -> runCatching { m.getParameterType(i + 1) }.getOrNull() }?.let { ps.setNull(i + 1, it) } ?: ps.setObject(i + 1, null)
                else -> ps.setObject(i + 1, v)
            }
        }
    }

    /** Auto mode: [body] and a commit, or a rollback. Manual mode: [body] joins the open transaction behind a savepoint that is undone on failure. */
    private fun transact(describe: () -> String?, body: (Connection) -> Long): Result<Long> {
        val c = try { connection() } catch (e: SQLException) { return Result.failure(e) }
        fun fail(e: Exception) = Result.failure<Long>(if (e is SQLException) SQLException("${e.message}\n${describe().orEmpty()}", e.sqlState, e.errorCode, e) else e)
        return if (autoCommit) {
            c.autoCommit = false
            try { val n = body(c); c.commit(); Result.success(n) }
            catch (e: Exception) { runCatching { c.rollback() }; fail(e) }
            finally { runCatching { c.autoCommit = true } }
        } else {
            val sp = c.setSavepoint()
            try { val n = body(c); pending = true; Result.success(n) }
            catch (e: Exception) { runCatching { c.rollback(sp) }; fail(e) }
        }
    }

    fun cancel() {
        cancelRequested = true
        runCatching { current?.cancel() }
    }

    /**
     * Runs each statement in order, stopping at the first failure (the failure is included).
     * Only the last result set stays open; earlier ones are drained to [keepRows] rows so
     * one script can show several grids without holding every cursor.
     */
    fun runScript(statements: List<String>, keepRows: Int = 500): List<StatementResult> {
        cancelRequested = false
        touch()
        activeCursor?.close(); activeCursor = null
        val results = mutableListOf<StatementResult>()
        for (sql in statements) {
            val r = runOne(sql)
            results += r
            if (r is StatementResult.Failed || cancelRequested) break
        }
        touch()
        return results.mapIndexed { i, r ->
            if (r is StatementResult.Rows && i != results.lastIndex) r.materialized(keepRows) else r
        }
    }

    private fun runOne(sql: String): StatementResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (options.readOnly) readOnlyViolation(sql)?.let { return StatementResult.Failed(sql, 0, it) }
        var st: Statement? = null
        try {
            st = connection().createStatement().also { current = it }
            // A cancel that arrived while connecting had no statement to hit yet.
            if (cancelRequested) {
                st.close()
                return StatementResult.Failed(sql, ms(), "Cancelled", cancelled = true)
            }
            val hasRows = st.execute(sql)
            if (!autoCommit && !isReadOnly(sql)) pending = true
            if (!hasRows) return StatementResult.Updated(sql, ms(), st.largeUpdateCountOrZero()).also { st.close() }
            val rs = st.resultSet
            val md = rs.metaData
            val cols = (1..md.columnCount).map { Column(md.getColumnLabel(it), md.getColumnTypeName(it)) }
            val cursor = JdbcCursor(st, rs, (1..md.columnCount).map { md.getColumnType(it) })
            activeCursor = cursor
            return StatementResult.Rows(sql, ms(), cols, cursor)
        } catch (e: SQLException) {
            runCatching { st?.close() }
            return StatementResult.Failed(sql, ms(), e.message ?: e.toString(), cancelled = cancelRequested)
        } finally {
            current = null
        }
    }

    override fun close() {
        activeCursor?.close()
        runCatching { conn?.close() }
        conn = null
    }

    private fun StatementResult.Rows.materialized(limit: Int): StatementResult.Rows {
        val page = cursor.fetch(limit)
        val cut = !cursor.exhausted
        cursor.close()
        return copy(cursor = ListCursor(page), truncated = cut)
    }
}

private class JdbcCursor(private val st: Statement, private val rs: ResultSet, private val types: List<Int>) : RowCursor {
    private val width = types.size

    override var exhausted = false
        private set

    override fun fetch(n: Int): List<List<Any?>> {
        if (exhausted) return emptyList()
        val rows = ArrayList<List<Any?>>(minOf(n, 1024))
        try {
            while (rows.size < n) {
                if (!rs.next()) { exhausted = true; break }
                rows += List(width) { value(it + 1, types[it]) }
            }
        } catch (e: SQLException) {
            exhausted = true
            if (rows.isEmpty()) throw e
        }
        if (exhausted) close()
        return rows
    }

    /** A cell as a plain Java value: drivers' own classes (Oracle's TIMESTAMPTZ, CLOB…) become standard ones the grid can show and edit. */
    private fun value(i: Int, type: Int): Any? = when (type) {
        java.sql.Types.CLOB, java.sql.Types.NCLOB, java.sql.Types.ROWID -> rs.getString(i)
        java.sql.Types.TIMESTAMP_WITH_TIMEZONE, ORACLE_TIMESTAMPTZ -> rs.getObject(i, java.time.OffsetDateTime::class.java)
        ORACLE_TIMESTAMPLTZ -> rs.getTimestamp(i)
        else -> rs.getObject(i).let { v -> if (v != null && v.javaClass.name.startsWith("oracle.")) rs.getString(i) else v }
    }

    @Volatile var isClosed = false
        private set

    override fun close() { isClosed = true; runCatching { rs.close() }; runCatching { st.close() } }
}

private const val ORACLE_TIMESTAMPTZ = -101
private const val ORACLE_TIMESTAMPLTZ = -102

/** Rows already read from an earlier result of a script; handed out once. */
private class ListCursor(private val rows: List<List<Any?>>) : RowCursor {
    override var exhausted = false
        private set
    override fun fetch(n: Int): List<List<Any?>> = if (exhausted) emptyList() else { exhausted = true; rows }
    override fun close() {}
}

private val READ_ONLY = setOf("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE", "USE", "SET")

/** Plain reads do not leave work to confirm; anything else (including WITH, which may wrap a write) does. */
private fun isReadOnly(sql: String) = firstKeyword(sql) in READ_ONLY

// Some drivers (Cassandra's) do not implement the "large" counters; their plain counters are fine for what a console shows.
private inline fun <T> countOr(large: () -> T, plain: () -> T): T =
    try { large() } catch (e: java.sql.SQLFeatureNotSupportedException) { plain() } catch (e: UnsupportedOperationException) { plain() }

private fun java.sql.PreparedStatement.updateCountOfExecution(): Long = countOr({ executeLargeUpdate() }, { executeUpdate().toLong() })
private fun Statement.largeUpdateCountOrZero(): Long = countOr({ largeUpdateCount }, { updateCount.toLong() }).coerceAtLeast(0)
