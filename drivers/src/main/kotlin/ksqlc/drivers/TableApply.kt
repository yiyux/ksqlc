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
class ApplyResult(val applied: Int, val error: String?)

/** Runs [statements] one by one, each committed on its own, stopping at the first that fails; [prelude] runs first and is not counted. */
fun runStepwise(ds: DataSource, password: String?, statements: List<String>, prelude: List<String> = emptyList()): ApplyResult =
    Session(ds, password, autoCommit = true).use { s ->
        for (sql in prelude) s.runScript(listOf(sql))
        for ((i, sql) in statements.withIndex()) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) return ApplyResult(i, "${r.message}\n$sql")
        }
        ApplyResult(statements.size, null)
    }
