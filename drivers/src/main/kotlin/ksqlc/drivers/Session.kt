package ksqlc.drivers

import ksqlc.core.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * One live connection, like a console session: transaction state and session settings persist
 * across statements. Statements run one at a time; [cancel] may be called from any thread.
 */
class Session(private val ds: DataSource, private val password: String?) : AutoCloseable {
    private var conn: Connection? = null
    @Volatile private var current: Statement? = null
    @Volatile private var cancelRequested = false
    @Volatile private var activeCursor: JdbcCursor? = null

    @Synchronized private fun connection(): Connection =
        conn?.takeIf { !it.isClosed } ?: DriverManager.getConnection(ds.url, ds.user, password).also { conn = it }

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
        activeCursor?.close(); activeCursor = null
        val results = mutableListOf<StatementResult>()
        for (sql in statements) {
            val r = runOne(sql)
            results += r
            if (r is StatementResult.Failed || cancelRequested) break
        }
        return results.mapIndexed { i, r ->
            if (r is StatementResult.Rows && i != results.lastIndex) r.materialized(keepRows) else r
        }
    }

    private fun runOne(sql: String): StatementResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var st: Statement? = null
        try {
            st = connection().createStatement().also { current = it }
            // A cancel that arrived while connecting had no statement to hit yet.
            if (cancelRequested) {
                st.close()
                return StatementResult.Failed(sql, ms(), "Cancelled", cancelled = true)
            }
            val hasRows = st.execute(sql)
            if (!hasRows) return StatementResult.Updated(sql, ms(), st.largeUpdateCount.coerceAtLeast(0)).also { st.close() }
            val rs = st.resultSet
            val md = rs.metaData
            val cols = (1..md.columnCount).map { Column(md.getColumnLabel(it), md.getColumnTypeName(it)) }
            val cursor = JdbcCursor(st, rs, cols.size)
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

private class JdbcCursor(private val st: Statement, private val rs: ResultSet, private val width: Int) : RowCursor {
    override var exhausted = false
        private set

    override fun fetch(n: Int): List<List<Any?>> {
        if (exhausted) return emptyList()
        val rows = ArrayList<List<Any?>>(minOf(n, 1024))
        try {
            while (rows.size < n) {
                if (!rs.next()) { exhausted = true; break }
                rows += List(width) { rs.getObject(it + 1) }
            }
        } catch (e: SQLException) {
            exhausted = true
            if (rows.isEmpty()) throw e
        }
        if (exhausted) close()
        return rows
    }

    override fun close() { runCatching { rs.close() }; runCatching { st.close() } }
}

/** Rows already read from an earlier result of a script; handed out once. */
private class ListCursor(private val rows: List<List<Any?>>) : RowCursor {
    override var exhausted = false
        private set
    override fun fetch(n: Int): List<List<Any?>> = if (exhausted) emptyList() else { exhausted = true; rows }
    override fun close() {}
}
