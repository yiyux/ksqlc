package ksqlc.core

/** Outcome of one statement in a script. */
sealed interface StatementResult {
    val sql: String
    val millis: Long

    /** Rows are fetched lazily ([truncated]: an earlier result of a script that was cut at the page limit); call [RowCursor.fetch] for more and [RowCursor.close] when done. */
    data class Rows(override val sql: String, override val millis: Long, val columns: List<Column>, val cursor: RowCursor, val truncated: Boolean = false) : StatementResult
    data class Updated(override val sql: String, override val millis: Long, val count: Long) : StatementResult
    data class Failed(override val sql: String, override val millis: Long, val message: String, val cancelled: Boolean = false) : StatementResult
}

interface RowCursor : AutoCloseable {
    /** Next page of up to [n] rows; empty once exhausted. */
    fun fetch(n: Int): List<List<Any?>>
    val exhausted: Boolean
}
