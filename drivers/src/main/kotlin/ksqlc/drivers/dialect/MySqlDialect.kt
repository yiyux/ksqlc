package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/** MySQL and MariaDB: a "namespace" is a database (catalog), there are no schemas in between. */
object MySqlDialect : MetaDialect() {
    override val hiddenNamespaces = setOf("mysql", "sys", "performance_schema", "information_schema")

    override fun quote(ident: String) = "`" + ident.replace("`", "``") + "`"
    override fun emptyInsert(table: String, firstColumn: String) = "insert into $table () values ()"

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.metaData.catalogs.list { Namespace(it.getString("TABLE_CAT"), null) }
            .filter { includeSystem || !isHidden(it) }.sortedBy { it.label.lowercase() }

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        val routines = conn.query(
            "select routine_name, routine_comment from information_schema.routines where routine_schema = ? order by routine_name",
            ns.catalog,
        ) { DbObject(ns, it.getString(1), DbObjectKind.ROUTINE, it.getString(2)?.ifBlank { null }) }
        return super.objects(conn, ns, includeSystem) + routines
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

    override fun call(conn: Connection, o: DbObject): List<String> {
        val params = conn.query(
            "select parameter_mode, parameter_name from information_schema.parameters where specific_schema = ? and specific_name = ? and ordinal_position > 0 order by ordinal_position",
            o.ns.catalog, o.name,
        ) { it.getString(1) to it.getString(2) }
        val holders = placeholders(params.map { it.second })
        if (routineType(conn, o) == "FUNCTION") return listOf("select ${qualified(o)}(${holders.joinToString(", ")}) as ${quote(o.name)}")
        // an OUT argument needs a session variable to hold it, shown afterwards; an INOUT one starts with the value asked for
        val before = ArrayList<String>(); val after = ArrayList<String>()
        val args = params.mapIndexed { i, (mode, _) ->
            val v = "@ksqlc_" + holders[i].drop(1)
            when (mode) {
                "OUT" -> v.also { after += "$v as ${holders[i].drop(1)}" }
                "INOUT" -> v.also { before += "set $v = ${holders[i]}"; after += "$v as ${holders[i].drop(1)}" }
                else -> holders[i]
            }
        }
        return before + "call ${qualified(o)}(${args.joinToString(", ")})" + listOfNotNull(after.takeIf { it.isNotEmpty() }?.let { "select ${it.joinToString(", ")}" })
    }

    private fun routineType(conn: Connection, o: DbObject): String =
        conn.query("select routine_type from information_schema.routines where routine_schema = ? and routine_name = ?", o.ns.catalog, o.name) { it.getString(1) }
            .firstOrNull() ?: "PROCEDURE"
}
