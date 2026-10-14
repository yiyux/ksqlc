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

/** A table opened from the explorer: its grid plus the private session that streams it. */
class DataTab(val obj: ksqlc.core.DbObject, val grid: ResultTab, private val session: ksqlc.drivers.Session) {
    fun close() { runCatching { grid.close() }; runCatching { session.close() } }
}
