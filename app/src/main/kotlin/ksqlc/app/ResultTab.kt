package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.StatementResult

/** One result grid. Rows accumulate page by page; [loadMore] blocks, so call it off the UI thread. */
class ResultTab(val title: String, val result: StatementResult.Rows) {
    val rows = mutableStateListOf<List<Any?>>()
    var exhausted by mutableStateOf(false)
    var loading by mutableStateOf(false)

    fun loadMore(pageSize: Int) {
        loading = true
        try {
            rows.addAll(result.cursor.fetch(pageSize))
            exhausted = result.cursor.exhausted
        } finally {
            loading = false
        }
    }

    fun close() = result.cursor.close()
}

/**
 * A table opened from the explorer: its grid, the private session that streams it, and the WHERE / ORDER BY the
 * user typed. [reload] re-runs `baseSql` with them; it blocks, so call it off the UI thread.
 */
class DataTab(val obj: ksqlc.core.DbObject, first: ResultTab, private val session: ksqlc.drivers.Session, private val baseSql: String) {
    var grid by mutableStateOf(first)
        private set
    var where by mutableStateOf("")
    var orderBy by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set

    fun query() = buildString {
        append(baseSql)
        if (where.isNotBlank()) append(" where ").append(where.trim())
        if (orderBy.isNotBlank()) append(" order by ").append(orderBy.trim())
    }

    /** Runs [query]; on success the grid is replaced, on failure the old grid stays and the error message is returned. */
    fun reload(): String? {
        loading = true
        try {
            when (val r = session.runScript(listOf(query()), PAGE).single()) {
                is StatementResult.Rows -> {
                    val next = ResultTab(grid.title, r).also { it.loadMore(PAGE) }
                    val old = grid
                    grid = next
                    runCatching { old.close() }
                    return null
                }
                is StatementResult.Failed -> return r.message
                else -> return "sin resultado"
            }
        } finally { loading = false }
    }

    fun close() { runCatching { grid.close() }; runCatching { session.close() } }
}
