package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/** One row of `system_schema.columns`: [kind] is `partition_key`, `clustering`, `regular` or `static`; [position] and [descending] only matter for the key kinds. */
data class CqlColumn(val name: String, val kind: String, val position: Int, val type: String, val descending: Boolean = false)

/** The columns in the order a `select *` returns them: partition key, clustering columns, then static and regular ones by name. */
fun cqlColumnOrder(columns: List<CqlColumn>): List<CqlColumn> =
    columns.sortedWith(compareBy({ when (it.kind) { "partition_key" -> 0; "clustering" -> 1; else -> 2 } }, { if (it.kind == "partition_key" || it.kind == "clustering") it.position else 0 }, { it.name }))

/** `CREATE TABLE` for a table read from the system schema; [quote] quotes identifiers. */
fun cqlCreateTable(keyspace: String, table: String, columns: List<CqlColumn>, comment: String?, quote: (String) -> String = { "\"" + it + "\"" }): String {
    val ordered = cqlColumnOrder(columns)
    val partition = ordered.filter { it.kind == "partition_key" }
    val clustering = ordered.filter { it.kind == "clustering" }
    val key = when {
        partition.size == 1 && clustering.isEmpty() -> quote(partition[0].name)
        partition.size == 1 -> (listOf(partition[0]) + clustering).joinToString(", ") { quote(it.name) }
        else -> "(" + partition.joinToString(", ") { quote(it.name) } + ")" + clustering.joinToString("") { ", " + quote(it.name) }
    }
    val body = ordered.joinToString(",\n") { "    ${quote(it.name)} ${it.type}${if (it.kind == "static") " static" else ""}" } + ",\n    PRIMARY KEY ($key)"
    val options = buildList {
        if (clustering.any { it.descending }) add("CLUSTERING ORDER BY (" + clustering.joinToString(", ") { quote(it.name) + if (it.descending) " DESC" else " ASC" } + ")")
        if (!comment.isNullOrBlank()) add("comment = '" + comment.replace("'", "''") + "'")
    }
    return "CREATE TABLE ${quote(keyspace)}.${quote(table)} (\n$body\n)" + if (options.isEmpty()) ";" else " WITH " + options.joinToString("\n    AND ") + ";"
}

/**
 * Apache Cassandra (CQL) through its `system_schema` tables: keyspaces as namespaces, tables, materialized views and
 * user functions. Cassandra has no foreign keys, no transactions and no default values, and its "primary key" is the
 * partition key followed by the clustering columns. DDL is rebuilt from the system schema (CQL has no `SHOW CREATE`).
 */
object CassandraDialect : MetaDialect() {
    private val system = setOf("system", "system_schema", "system_auth", "system_distributed", "system_traces", "system_views", "system_virtual_schema")

    override fun emptyInsert(table: String, firstColumn: String) = "insert into $table ($firstColumn) values (null)"

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.query("select keyspace_name from system_schema.keyspaces") { Namespace(null, it.getString(1)) }
            .filter { includeSystem || it.label !in system }.sortedBy { it.label.lowercase() }

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        val tables = conn.query("select table_name, comment from system_schema.tables where keyspace_name = ?", ns.schema) { DbObject(ns, it.getString(1), DbObjectKind.TABLE, it.getString(2)?.ifBlank { null }) }
        val views = conn.query("select view_name, comment from system_schema.views where keyspace_name = ?", ns.schema) { DbObject(ns, it.getString(1), DbObjectKind.MATERIALIZED_VIEW, it.getString(2)?.ifBlank { null }) }
        val functions = conn.query("select function_name from system_schema.functions where keyspace_name = ?", ns.schema) { it.getString(1) }.distinct()
            .map { DbObject(ns, it, DbObjectKind.ROUTINE) }
        return (tables + views).sortedBy { it.name.lowercase() } + functions.sortedBy { it.name.lowercase() }
    }

    private fun columns(conn: Connection, o: DbObject): List<CqlColumn> =
        conn.query("select column_name, kind, position, type, clustering_order from system_schema.columns where keyspace_name = ? and table_name = ?", o.ns.schema, o.name) {
            CqlColumn(it.getString(1), it.getString(2), it.getInt(3), it.getString(4), it.getString(5).equals("desc", true))
        }

    override fun details(conn: Connection, o: DbObject): TableDetails {
        val cols = cqlColumnOrder(columns(conn, o))
        val key = cols.filter { it.kind == "partition_key" || it.kind == "clustering" }.map { it.name }
        val indexes = if (o.kind == DbObjectKind.TABLE) conn.query("select index_name, options from system_schema.indexes where keyspace_name = ? and table_name = ?", o.ns.schema, o.name) {
            IndexInfo(it.getString(1), false, listOfNotNull((it.getObject(2) as? Map<*, *>)?.get("target")?.toString()))
        } else emptyList()
        return TableDetails(
            columns = cols.map { ColumnInfo(it.name, it.type, nullable = it.name !in key) },
            primaryKey = key, indexes = indexes,
        )
    }

    override fun ddl(conn: Connection, o: DbObject): String {
        val ks = o.ns.schema ?: error("${o.name} no tiene keyspace")
        return ddl(conn, o, ks)
    }

    private fun ddl(conn: Connection, o: DbObject, ks: String): String = when (o.kind) {
        DbObjectKind.TABLE -> {
            val comment = conn.query("select comment from system_schema.tables where keyspace_name = ? and table_name = ?", o.ns.schema, o.name) { it.getString(1) }.firstOrNull()
            val table = cqlCreateTable(ks, o.name, columns(conn, o), comment, ::quote)
            val indexes = conn.query("select index_name, kind, options from system_schema.indexes where keyspace_name = ? and table_name = ?", o.ns.schema, o.name) { rs ->
                val options = rs.getObject(3) as? Map<*, *>
                val target = options?.get("target")?.toString().orEmpty()
                "CREATE ${if (rs.getString(2) == "CUSTOM") "CUSTOM " else ""}INDEX ${quote(rs.getString(1))} ON ${quote(ks)}.${quote(o.name)} ($target)" +
                    (if (rs.getString(2) == "CUSTOM") " USING '${options?.get("class_name")}'" else "") + ";"
            }
            (listOf(table) + indexes).joinToString("\n\n")
        }
        DbObjectKind.MATERIALIZED_VIEW -> {
            val v = conn.query("select base_table_name, where_clause, include_all_columns from system_schema.views where keyspace_name = ? and view_name = ?", o.ns.schema, o.name) {
                Triple(it.getString(1), it.getString(2), it.getBoolean(3))
            }.firstOrNull() ?: error("${o.name} no existe")
            val cols = cqlColumnOrder(columns(conn, o))
            val partition = cols.filter { it.kind == "partition_key" }.map { quote(it.name) }
            val clustering = cols.filter { it.kind == "clustering" }.map { quote(it.name) }
            val key = if (partition.size == 1) (partition + clustering).joinToString(", ") else "(" + partition.joinToString(", ") + ")" + clustering.joinToString("") { ", $it" }
            "CREATE MATERIALIZED VIEW ${quote(ks)}.${quote(o.name)} AS\n    SELECT ${if (v.third) "*" else cols.joinToString(", ") { quote(it.name) }}\n    FROM ${quote(ks)}.${quote(v.first)}\n" +
                "    WHERE ${v.second}\n    PRIMARY KEY ($key);"
        }
        DbObjectKind.ROUTINE -> conn.query(
            "select argument_names, argument_types, return_type, language, body, called_on_null_input from system_schema.functions where keyspace_name = ? and function_name = ?", o.ns.schema, o.name,
        ) { rs ->
            val names = (rs.getObject(1) as? Collection<*>).orEmpty(); val types = (rs.getObject(2) as? Collection<*>).orEmpty()
            val args = names.zip(types).joinToString(", ") { (n, t) -> "$n $t" }
            "CREATE OR REPLACE FUNCTION ${quote(ks)}.${quote(o.name)} ($args)\n    ${if (rs.getBoolean(6)) "CALLED ON NULL INPUT" else "RETURNS NULL ON NULL INPUT"}\n    RETURNS ${rs.getString(3)}\n    LANGUAGE ${rs.getString(4)}\n    AS \$\$${rs.getString(5)}\$\$;"
        }.joinToString("\n\n").ifBlank { error("${o.name} no existe") }
        DbObjectKind.VIEW, DbObjectKind.SEQUENCE -> error("Cassandra no tiene ${if (o.kind == DbObjectKind.VIEW) "vistas simples" else "secuencias"}")
    }
}
