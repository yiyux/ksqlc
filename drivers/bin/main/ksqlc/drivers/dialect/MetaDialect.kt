package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/** Introspection on top of [DatabaseMetaData]; engines override what the standard API gets wrong or lacks. */
abstract class MetaDialect : Dialect {
    protected open val hiddenNamespaces: Set<String> = emptySet()
    protected open val tableTypes: Array<String> = arrayOf("TABLE", "VIEW")

    override fun quote(ident: String) = "\"" + ident.replace("\"", "\"\"") + "\""

    override fun qualified(o: DbObject): String =
        (o.ns.schema ?: o.ns.catalog)?.let { quote(it) + "." }.orEmpty() + quote(o.name)

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.metaData.schemas.use { rs ->
            buildList { while (rs.next()) add(Namespace(null, rs.getString("TABLE_SCHEM"))) }
        }.filter { includeSystem || !isHidden(it) }.sortedBy { it.label.lowercase() }

    protected open fun isHidden(ns: Namespace) = ns.label.lowercase() in hiddenNamespaces

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        val meta = conn.metaData
        // with system objects asked for, every table type: MySQL calls the tables of its own information_schema SYSTEM VIEW
        return meta.getTables(ns.catalog, ns.schema?.let { esc(meta, it) }, "%", if (includeSystem) null else tableTypes).use { rs ->
            buildList {
                while (rs.next()) kindOf(rs.getString("TABLE_TYPE"))?.let {
                    add(DbObject(ns, rs.getString("TABLE_NAME"), it, rs.getString("REMARKS")?.ifBlank { null }))
                }
            }
        }.sortedBy { it.name.lowercase() }
    }

    protected open fun kindOf(type: String?): DbObjectKind? = when (type?.uppercase()) {
        "TABLE", "BASE TABLE", "PARTITIONED TABLE", "FOREIGN TABLE" -> DbObjectKind.TABLE
        "VIEW", "SYSTEM VIEW" -> DbObjectKind.VIEW
        "MATERIALIZED VIEW" -> DbObjectKind.MATERIALIZED_VIEW
        "SEQUENCE" -> DbObjectKind.SEQUENCE
        else -> null
    }

    override fun details(conn: Connection, o: DbObject): TableDetails {
        val meta = conn.metaData
        val columns = meta.getColumns(o.ns.catalog, o.ns.schema?.let { esc(meta, it) }, esc(meta, o.name), "%").use { rs ->
            buildList {
                while (rs.next()) add(
                    ColumnInfo(
                        name = rs.getString("COLUMN_NAME"),
                        typeName = rs.getString("TYPE_NAME"),
                        size = rs.getInt("COLUMN_SIZE").takeUnless { rs.wasNull() },
                        scale = rs.getInt("DECIMAL_DIGITS").takeUnless { rs.wasNull() },
                        nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        default = rs.getString("COLUMN_DEF"),
                        autoIncrement = rs.getString("IS_AUTOINCREMENT") == "YES",
                    ) to rs.getInt("ORDINAL_POSITION")
                )
            }
        }.sortedBy { it.second }.map { it.first }
        if (o.kind != DbObjectKind.TABLE) return TableDetails(columns)

        var pkName: String? = null
        val pk = meta.getPrimaryKeys(o.ns.catalog, o.ns.schema, o.name).use { rs ->
            buildList { while (rs.next()) { pkName = rs.getString("PK_NAME"); add(rs.getInt("KEY_SEQ") to rs.getString("COLUMN_NAME")) } }
        }.sortedBy { it.first }.map { it.second }
        return TableDetails(columns, pkName, pk, indexes(meta, o), foreignKeys(meta, o))
    }

    private fun indexes(meta: DatabaseMetaData, o: DbObject): List<IndexInfo> {
        data class Row(val name: String, val unique: Boolean, val pos: Int, val col: String)
        val rows = meta.getIndexInfo(o.ns.catalog, o.ns.schema, o.name, false, true).use { rs ->
            buildList {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue
                    val name = rs.getString("INDEX_NAME") ?: continue
                    add(Row(name, !rs.getBoolean("NON_UNIQUE"), rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME") ?: "<expr>"))
                }
            }
        }
        return rows.groupBy { it.name }.map { (name, rs) ->
            IndexInfo(name, rs.first().unique, rs.sortedBy { it.pos }.map { it.col })
        }.sortedBy { it.name.lowercase() }
    }

    private fun foreignKeys(meta: DatabaseMetaData, o: DbObject): List<ForeignKeyInfo> {
        data class Row(val name: String?, val ref: Namespace, val refTable: String, val seq: Int, val col: String, val refCol: String)
        val rows = meta.getImportedKeys(o.ns.catalog, o.ns.schema, o.name).use { rs ->
            buildList {
                while (rs.next()) add(
                    Row(
                        rs.getString("FK_NAME"),
                        Namespace(rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM")),
                        rs.getString("PKTABLE_NAME"), rs.getInt("KEY_SEQ"),
                        rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME"),
                    )
                )
            }
        }
        return rows.groupBy { Triple(it.name, it.ref, it.refTable) }.map { (k, rs) ->
            val sorted = rs.sortedBy { it.seq }
            ForeignKeyInfo(k.first, sorted.map { it.col }, k.second, k.third, sorted.map { it.refCol })
        }.sortedBy { it.name.orEmpty().lowercase() }
    }

    override fun ddl(conn: Connection, o: DbObject): String = generateDdl(o, details(conn, o))

    /** `CREATE TABLE`/`CREATE INDEX` text built from introspected metadata, for engines without a native "show create". */
    protected fun generateDdl(o: DbObject, d: TableDetails): String {
        val name = qualified(o)
        if (o.kind == DbObjectKind.SEQUENCE) return "create sequence $name;"
        val lines = d.columns.map { c ->
            buildString {
                append("    ", quote(c.name), " ", typeText(c))
                if (!c.nullable) append(" not null")
                if (c.default != null) append(" default ", c.default)
            }
        }.toMutableList()
        if (d.primaryKey.isNotEmpty()) {
            lines += "    " + (d.primaryKeyName?.let { "constraint ${quote(it)} " } ?: "") +
                "primary key (${d.primaryKey.joinToString { quote(it) }})"
        }
        for (fk in d.foreignKeys) {
            val ref = qualified(DbObject(fk.refNs, fk.refTable, DbObjectKind.TABLE))
            lines += "    " + (fk.name?.let { "constraint ${quote(it)} " } ?: "") +
                "foreign key (${fk.columns.joinToString { quote(it) }}) references $ref (${fk.refColumns.joinToString { quote(it) }})"
        }
        val sb = StringBuilder()
        sb.append("create table ").append(name).append(" (\n").append(lines.joinToString(",\n")).append("\n);")
        for (ix in d.indexes.filter { it.name != d.primaryKeyName }) {
            sb.append("\n\ncreate ").append(if (ix.unique) "unique " else "").append("index ").append(quote(ix.name))
                .append(" on ").append(name).append(" (").append(ix.columns.joinToString { quote(it) }).append(");")
        }
        return sb.toString()
    }

    private fun typeText(c: ColumnInfo): String {
        val sized = c.typeName.lowercase() in SIZED_TYPES
        val size = c.size?.takeIf { sized && it in 1..100_000 }
        val scale = c.scale ?: 0
        return when {
            size == null -> c.typeName
            scale > 0 && c.typeName.lowercase() in setOf("numeric", "decimal") -> "${c.typeName}($size,$scale)"
            else -> "${c.typeName}($size)"
        }
    }

    /** Escapes `_` and `%` so a name can be used as a metadata pattern without matching other objects. */
    protected fun esc(meta: DatabaseMetaData, s: String): String {
        val e = meta.searchStringEscape ?: return s
        return s.replace(e, e + e).replace("_", e + "_").replace("%", e + "%")
    }

    protected fun <T> ResultSet.list(f: (ResultSet) -> T): List<T> {
        val rs = this
        return rs.use { buildList { while (rs.next()) add(f(rs)) } }
    }

    /** Runs a parameterised query and maps every row. */
    protected fun <T> Connection.query(sql: String, vararg args: Any?, f: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().list(f)
        }

    private companion object {
        val SIZED_TYPES = setOf(
            "varchar", "char", "character", "character varying", "bpchar", "nvarchar", "nchar",
            "varbinary", "binary", "numeric", "decimal", "bit", "varbit",
        )
    }
}
