package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/** Per-engine differences in introspection, identifier quoting and DDL. All methods take an open connection. */
interface Dialect {
    fun quote(ident: String): String
    fun qualified(o: DbObject): String
    fun selectAll(o: DbObject): String = "select * from ${qualified(o)}"

    /**
     * An insert that supplies no values at all (every column takes its default). [table] is the qualified name and
     * [firstColumn] the quoted name of one of its columns, for engines that need at least one to say `default`.
     */
    fun emptyInsert(table: String, firstColumn: String): String = "insert into $table default values"

    /** [includeSystem] adds the engine's own schemas and objects (`pg_catalog`, `sys`, `mysql`…) that are hidden by default. */
    fun namespaces(conn: Connection, includeSystem: Boolean = false): List<Namespace>
    fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean = false): List<DbObject>
    fun details(conn: Connection, o: DbObject): TableDetails
    fun ddl(conn: Connection, o: DbObject): String

    /**
     * The statements that call routine [o]. Each input parameter is a `:name` placeholder, so the console asks for the values
     * before running; the parameters it cannot take (OUT ones, where the engine needs a variable to hold them) make this fail.
     */
    fun call(conn: Connection, o: DbObject): List<String> = error("Este motor no tiene rutinas que ejecutar")

    /** An editable copy of table [o], or null when the engine's tables cannot be modified yet. */
    fun tableModel(conn: Connection, o: DbObject): TableModel? = null

    /** The statements that turn [before] into [after] on table [o]; empty when nothing changed. */
    fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = error("Este motor no puede modificar tablas")

    companion object {
        fun of(kind: DbKind): Dialect = when (kind) {
            DbKind.POSTGRES -> PostgresDialect
            DbKind.MYSQL, DbKind.MARIADB -> MySqlDialect
            DbKind.SQLITE -> SqliteDialect
            DbKind.MSSQL -> SqlServerDialect
            DbKind.ORACLE -> OracleDialect
            DbKind.CASSANDRA -> CassandraDialect
        }
    }
}

/** The `:name` placeholder of each parameter: its name made safe for [ksqlc.sql.findParams], or `argN` when it has none, never repeated. */
internal fun placeholders(names: List<String?>): List<String> {
    val seen = HashSet<String>()
    return names.mapIndexed { i, raw ->
        val base = raw.orEmpty().removePrefix("@").replace(Regex("\\W"), "_").ifEmpty { "arg${i + 1}" }.let { if (it[0].isDigit()) "_$it" else it }
        var n = base; var k = 2
        while (!seen.add(n.lowercase())) n = base + "_" + k++
        ":$n"
    }
}
