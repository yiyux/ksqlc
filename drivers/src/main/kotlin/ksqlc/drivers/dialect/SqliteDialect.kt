package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/** SQLite has no useful `DatabaseMetaData`; everything comes from `sqlite_master` and PRAGMAs. */
object SqliteDialect : MetaDialect() {
    override fun namespaces(conn: Connection): List<Namespace> =
        conn.createStatement().use { st ->
            st.executeQuery("pragma database_list").list { Namespace(null, it.getString("name")) }
        }.filter { it.schema != "temp" }

    override fun objects(conn: Connection, ns: Namespace): List<DbObject> =
        conn.query(
            "select name, type from ${quote(ns.schema!!)}.sqlite_master where type in ('table','view') and name not like 'sqlite\\_%' escape '\\' order by name collate nocase",
        ) { DbObject(ns, it.getString(1), if (it.getString(2) == "view") DbObjectKind.VIEW else DbObjectKind.TABLE) }

    override fun details(conn: Connection, o: DbObject): TableDetails {
        val schema = quote(o.ns.schema!!)
        fun pragma(name: String, arg: String) = "pragma $schema.$name(${quote(arg)})"
        data class Col(val info: ColumnInfo, val pk: Int)
        val cols = conn.createStatement().use { st ->
            st.executeQuery(pragma("table_info", o.name)).list {
                Col(ColumnInfo(it.getString("name"), it.getString("type").orEmpty(), nullable = it.getInt("notnull") == 0, default = it.getString("dflt_value")), it.getInt("pk"))
            }
        }
        if (o.kind != DbObjectKind.TABLE) return TableDetails(cols.map { it.info })

        val pk = cols.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.info.name }
        val indexes = conn.createStatement().use { st ->
            st.executeQuery(pragma("index_list", o.name)).list { Triple(it.getString("name"), it.getInt("unique") == 1, it.getString("origin")) }
        }.filter { it.third != "pk" }.map { (name, unique, _) ->
            val columns = conn.createStatement().use { st ->
                st.executeQuery(pragma("index_info", name)).list { it.getInt("seqno") to (it.getString("name") ?: "<expr>") }
            }.sortedBy { it.first }.map { it.second }
            IndexInfo(name, unique, columns)
        }
        data class Fk(val id: Int, val seq: Int, val table: String, val from: String, val to: String?)
        val fks = conn.createStatement().use { st ->
            st.executeQuery(pragma("foreign_key_list", o.name)).list { Fk(it.getInt("id"), it.getInt("seq"), it.getString("table"), it.getString("from"), it.getString("to")) }
        }.groupBy { it.id }.values.map { rows ->
            val r = rows.sortedBy { it.seq }
            ForeignKeyInfo(null, r.map { it.from }, o.ns, r.first().table, r.mapNotNull { it.to })
        }
        return TableDetails(cols.map { it.info }, null, pk, indexes, fks)
    }

    override fun ddl(conn: Connection, o: DbObject): String {
        val master = "${quote(o.ns.schema!!)}.sqlite_master"
        val own = conn.query("select sql from $master where name = ? and type in ('table','view')", o.name) { it.getString(1) }.firstOrNull()
            ?: error("${o.name} no existe")
        val indexes = conn.query("select sql from $master where tbl_name = ? and type = 'index' and sql is not null order by name", o.name) { it.getString(1) }
        return (listOf(own) + indexes).joinToString(";\n\n", postfix = ";")
    }
}
