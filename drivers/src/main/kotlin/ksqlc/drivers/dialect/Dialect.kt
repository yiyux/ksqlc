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

    fun namespaces(conn: Connection): List<Namespace>
    fun objects(conn: Connection, ns: Namespace): List<DbObject>
    fun details(conn: Connection, o: DbObject): TableDetails
    fun ddl(conn: Connection, o: DbObject): String

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
