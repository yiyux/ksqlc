package ksqlc.drivers

import ksqlc.core.DbKind

/** What to run to see a plan: [statements] in order (the last ones give the plan rows), then [cleanup] whatever happened. */
class ExplainScript(val statements: List<String>, val cleanup: List<String> = emptyList())

/**
 * The statements that show how [kind]'s server would run [sql] (one statement, no trailing `;`). With [analyze] the statement
 * is really executed and timings are included. Null when the engine has no such thing here (analyze on SQLite, SQL Server and Oracle).
 */
fun explainScript(kind: DbKind, sql: String, analyze: Boolean): ExplainScript? = when (kind) {
    DbKind.POSTGRES -> ExplainScript(listOf(if (analyze) "explain (analyze, buffers) $sql" else "explain $sql"))
    DbKind.MYSQL -> ExplainScript(listOf(if (analyze) "explain analyze $sql" else "explain $sql"))
    DbKind.MARIADB -> ExplainScript(listOf(if (analyze) "analyze $sql" else "explain $sql"))
    DbKind.SQLITE -> if (analyze) null else ExplainScript(listOf("explain query plan $sql"))
    // SHOWPLAN makes the server describe statements instead of running them; it has to be switched off again even after an error
    DbKind.MSSQL -> if (analyze) null else ExplainScript(listOf("set showplan_text on", sql), cleanup = listOf("set showplan_text off"))
    DbKind.TRINO -> ExplainScript(listOf((if (analyze) "explain analyze " else "explain ") + sql))
    DbKind.CASSANDRA -> null      // CQL has no EXPLAIN; use TRACING in cqlsh
    DbKind.ORACLE -> if (analyze) null else ExplainScript(listOf("explain plan for $sql", "select plan_table_output from table(dbms_xplan.display())"))
}
