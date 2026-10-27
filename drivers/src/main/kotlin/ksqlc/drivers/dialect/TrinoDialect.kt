package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/**
 * Trino through JDBC metadata: a namespace is a catalog plus a schema (the same schema name repeats across catalogs).
 * Trino reports no keys or indexes and has no transactions, so a table shows its columns only.
 */
object TrinoDialect : MetaDialect() {
    override fun qualified(o: DbObject): String =
        listOfNotNull(o.ns.catalog, o.ns.schema, o.name).joinToString(".") { quote(it) }

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> {
        val meta = conn.metaData
        val catalogs = meta.catalogs.list { it.getString(1) }.filter { includeSystem || it != "system" }
        return catalogs.flatMap { cat -> meta.getSchemas(cat, null).list { Namespace(cat, it.getString("TABLE_SCHEM")) } }
            .filter { includeSystem || it.schema != "information_schema" }
            .sortedWith(compareBy({ it.catalog!!.lowercase() }, { it.schema!!.lowercase() }))
    }

    override fun details(conn: Connection, o: DbObject): TableDetails = super.details(conn, o.copy(kind = DbObjectKind.VIEW))

    override fun ddl(conn: Connection, o: DbObject): String {
        val what = when (o.kind) {
            DbObjectKind.TABLE -> "table"
            DbObjectKind.VIEW -> "view"
            DbObjectKind.MATERIALIZED_VIEW -> "materialized view"
            else -> error("Trino no tiene ${o.name} como objeto con DDL")
        }
        return conn.createStatement().use { st -> st.executeQuery("show create $what ${qualified(o)}").use { rs -> rs.next(); rs.getString(1) } }
    }
}
