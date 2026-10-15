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
