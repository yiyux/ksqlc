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

    override val modifyCaps = ModifyCaps(
        atomic = false, deferrable = false, indexExtras = false, identityOptions = false, singleIdentity = true,
        virtualColumns = true, refInCatalog = true, indexMethods = listOf("btree", "hash"),
    )

    override fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = mysqlAlterTable(this, o, before, after)

    override fun tableModel(conn: Connection, o: DbObject): TableModel {
        val db = o.ns.catalog
        val mariaDb = conn.metaData.databaseProductVersion.contains("MariaDB", ignoreCase = true)
        val (comment, tableCollation) = conn.query("select table_comment, table_collation from information_schema.tables where table_schema = ? and table_name = ?", db, o.name) { it.getString(1) to it.getString(2) }
            .firstOrNull() ?: error("${o.name} no existe")

        val columns = conn.query(
            """select column_name, column_type, is_nullable, column_default, extra, generation_expression, column_comment, data_type, collation_name
               from information_schema.columns where table_schema = ? and table_name = ? order by ordinal_position""",
            db, o.name,
        ) { rs ->
            val rawExtra = rs.getString(5).orEmpty()
            val extra = rawExtra.lowercase()
            val generated = "generated" in extra && "default_generated" !in extra
            // a modify column rewrites the whole definition, so what the server keeps beside the type has to travel in the text
            val collation = rs.getString(9)?.takeIf { it != tableCollation }
            val onUpdate = Regex("""on update\s+\S+""", RegexOption.IGNORE_CASE).find(rawExtra)?.value
            val default = if (generated) null else defaultText(rs.getString(4), extra, rs.getString(8), mariaDb, rs.getString(3) == "YES")
            ColumnModel(
                rs.getString(1), rs.getString(1), columnType(rs.getString(2), mariaDb) + (collation?.let { " collate $it" } ?: ""), rs.getString(3) == "NO",
                if (onUpdate != null && !generated) listOfNotNull(default, onUpdate).joinToString(" ") else default,
                rs.getString(7)?.ifEmpty { null },
                if ("auto_increment" in extra) Identity() else null,
                if (generated) rs.getString(6) else null,
                "virtual" !in extra,
            )
        }

        // the `expression` column of `statistics` exists from MySQL 8.0.13 only
        val hasExpression = conn.query(
            "select 1 from information_schema.columns where upper(table_schema) = 'INFORMATION_SCHEMA' and upper(table_name) = 'STATISTICS' and upper(column_name) = 'EXPRESSION'",
        ) { 1 }.isNotEmpty()
        class Part(val index: String, val nonUnique: Boolean, val name: String?, val desc: Boolean, val type: String, val expression: String?, val prefix: Boolean)
        val parts = conn.query(
            "select index_name, non_unique, column_name, collation, index_type, sub_part, " + (if (hasExpression) "expression" else "null") +
                " from information_schema.statistics where table_schema = ? and table_name = ? order by index_name, seq_in_index",
            db, o.name,
        ) { rs -> Part(rs.getString(1), rs.getInt(2) != 0, rs.getString(3), rs.getString(4) == "D", rs.getString(5).uppercase(), rs.getString(7), rs.getObject(6) != null) }
        // fulltext and spatial indexes and prefix parts are not modeled: leaving them out means they are never dropped or recreated
        val usable = parts.groupBy { it.index }.filterValues { ps -> ps.none { it.prefix } && ps.first().type in setOf("BTREE", "HASH") }
        // a unique key with an expression part is left out too: dropping the expression would change what it means
        val keys = usable.filterValues { !it.first().nonUnique && it.all { p -> p.name != null } }.map { (name, ps) ->
            KeyModel(name, name, name == "PRIMARY", ps.map { it.name!! })
        }.sortedWith(compareBy({ !it.primary }, { it.name }))
        val indexes = usable.filterValues { it.first().nonUnique }.map { (name, ps) ->
            IndexModel(name, name, false, ps.first().type.lowercase(),
                ps.map { if (it.name != null) IndexColumn(it.name, order = if (it.desc) "DESC" else "") else IndexColumn(it.expression.orEmpty(), expression = true, order = if (it.desc) "DESC" else "") })
        }.sortedBy { it.name }

        val foreignKeys = conn.query(
            """select k.constraint_name, k.column_name, k.referenced_table_schema, k.referenced_table_name, k.referenced_column_name, r.update_rule, r.delete_rule
               from information_schema.key_column_usage k
               join information_schema.referential_constraints r
                 on r.constraint_schema = k.constraint_schema and r.constraint_name = k.constraint_name and r.table_name = k.table_name
               where k.table_schema = ? and k.table_name = ? and k.referenced_table_name is not null
               order by k.constraint_name, k.ordinal_position""",
            db, o.name,
        ) { rs -> arrayOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) }
            .groupBy { it[0] }.map { (name, rows) ->
                ForeignKeyModel(name, name, rows.map { it[1] }, Namespace(rows.first()[2], null), rows.first()[3], rows.map { it[4] }, rows.first()[6], rows.first()[5])
            }

        val checks = conn.query(
            """select cc.constraint_name, cc.check_clause from information_schema.check_constraints cc
               join information_schema.table_constraints tc on tc.constraint_schema = cc.constraint_schema and tc.constraint_name = cc.constraint_name
               where tc.table_schema = ? and tc.table_name = ? and tc.constraint_type = 'CHECK' order by cc.constraint_name""",
            db, o.name,
        ) { CheckModel(it.getString(1), it.getString(1), it.getString(2)) }

        return TableModel(o.name, comment.ifEmpty { null }, columns, keys, foreignKeys, indexes, checks)
    }

    // MariaDB gives literals and expressions both as written, MySQL gives plain text plus a DEFAULT_GENERATED flag; either way the result must paste after `default `.
    // MariaDB reports a display width (bigint(20)) that MySQL 8 no longer does; tinyint(1) stays as the boolean idiom and zerofill keeps its width (it is the padding)
    private fun columnType(t: String, mariaDb: Boolean) =
        if (mariaDb && !t.contains("zerofill", ignoreCase = true)) t.replace(Regex("^(smallint|mediumint|int|bigint|tinyint)\\((\\d+)\\)")) { if (it.value == "tinyint(1)") it.value else it.groupValues[1] } else t

    private fun defaultText(raw: String?, extra: String, dataType: String, mariaDb: Boolean, nullable: Boolean): String? {
        if (raw == null) return null
        val ready = Regex("""'.*'|-?\d+(\.\d+)?([eE][-+]?\d+)?|[bBxX]'[01a-fA-F]*'|0[xX][0-9a-fA-F]+|current_timestamp(\(\d*\))?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        if (mariaDb) return when {
            raw.equals("NULL", ignoreCase = true) && nullable -> null
            raw.matches(ready) -> raw
            else -> "($raw)"
        }
        if ("default_generated" in extra) return if (raw.startsWith("CURRENT_TIMESTAMP", ignoreCase = true)) raw else "($raw)"
        val numeric = dataType.lowercase() in setOf("tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "numeric", "float", "double")
        return if (numeric || (dataType.equals("bit", ignoreCase = true) && raw.matches(Regex("""[bB]'[01]*'""")))) raw else "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'"
    }

    private fun routineType(conn: Connection, o: DbObject): String =
        conn.query("select routine_type from information_schema.routines where routine_schema = ? and routine_name = ?", o.ns.catalog, o.name) { it.getString(1) }
            .firstOrNull() ?: "PROCEDURE"
}
