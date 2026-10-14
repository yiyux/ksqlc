package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/** MySQL and MariaDB: a "namespace" is a database (catalog), there are no schemas in between. */
object MySqlDialect : MetaDialect() {
    override val hiddenNamespaces = setOf("mysql", "sys", "performance_schema", "information_schema")

    override fun quote(ident: String) = "`" + ident.replace("`", "``") + "`"
    override fun emptyInsert(table: String) = "insert into $table () values ()"

    override fun namespaces(conn: Connection): List<Namespace> =
        conn.metaData.catalogs.list { Namespace(it.getString("TABLE_CAT"), null) }
            .filterNot(::isHidden).sortedBy { it.label.lowercase() }

    override fun objects(conn: Connection, ns: Namespace): List<DbObject> {
        val routines = conn.query(
            "select routine_name, routine_comment from information_schema.routines where routine_schema = ? order by routine_name",
            ns.catalog,
        ) { DbObject(ns, it.getString(1), DbObjectKind.ROUTINE, it.getString(2)?.ifBlank { null }) }
        return super.objects(conn, ns) + routines
    }

    override fun ddl(conn: Connection, o: DbObject): String {
        val what = when (o.kind) {
            DbObjectKind.TABLE -> "TABLE"
            DbObjectKind.VIEW, DbObjectKind.MATERIALIZED_VIEW -> "VIEW"
            DbObjectKind.SEQUENCE -> "SEQUENCE"
            DbObjectKind.ROUTINE -> routineType(conn, o)
        }
        // Column 1 is the name; the statement is in column 2, except for routines where it is column 3.
        val col = if (o.kind == DbObjectKind.ROUTINE) 3 else 2
        return conn.createStatement().use { st ->
            st.executeQuery("show create $what ${qualified(o)}").use { rs ->
                if (rs.next()) rs.getString(col).trimEnd() + ";" else error("${o.name} no existe")
            }
        }
    }

    private fun routineType(conn: Connection, o: DbObject): String =
        conn.query("select routine_type from information_schema.routines where routine_schema = ? and routine_name = ?", o.ns.catalog, o.name) { it.getString(1) }
            .firstOrNull() ?: "PROCEDURE"
}
