package ksqlc.drivers

import ksqlc.core.DataSource
import ksqlc.core.StatementResult

/** Runs [statements] in one transaction on a connection of its own: null when all went through, else the error of the first that failed (everything undone). */
fun runAtomically(ds: DataSource, password: String?, statements: List<String>): String? =
    Session(ds, password, autoCommit = false).use { s ->
        for (sql in statements) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) { s.rollback(); return "${r.message}\n$sql" }
        }
        s.commit()
        null
    }

/** [applied] statements ran before [error] (null when all went through); the engine had already committed each one. */
class ApplyResult(val applied: Int, val error: String?, val preludeFailed: Boolean = false)

/** Runs [statements] one by one, each committed on its own, stopping at the first that fails; [prelude] runs first and is not counted. */
fun runStepwise(ds: DataSource, password: String?, statements: List<String>, prelude: List<String> = emptyList()): ApplyResult =
    Session(ds, password, autoCommit = true).use { s ->
        for (sql in prelude) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) return ApplyResult(0, "${r.message}\n$sql", preludeFailed = true)
        }
        for ((i, sql) in statements.withIndex()) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) return ApplyResult(i, "${r.message}\n$sql")
        }
        ApplyResult(statements.size, null)
    }

/** Session settings for a MySQL/MariaDB stepwise apply; the generated text doubles backslashes, which NO_BACKSLASH_ESCAPES would take literally. */
val mySqlPrelude = listOf("set session lock_wait_timeout = 5", "set session sql_mode = replace(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '')")
