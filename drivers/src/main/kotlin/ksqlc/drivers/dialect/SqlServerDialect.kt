package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

/**
 * SQL Server through its catalog views (`sys.*`): `[bracket]` quoting, schemas without the system and fixed-role ones,
 * tables, views, sequences and procedures/functions with their `MS_Description` comments, identity and computed columns,
 * clustered/included-column indexes, foreign-key actions, and the original text of views and routines for the DDL.
 */
object SqlServerDialect : MetaDialect() {
    override fun quote(ident: String) = "[" + ident.replace("]", "]]") + "]"

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.query(
            "select name from sys.schemas where name not in (${if (includeSystem) "'guest'" else "'sys', 'INFORMATION_SCHEMA', 'guest'"}) and name not like 'db[_]%' order by name",
        ) { Namespace(null, it.getString(1)) }

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        // the engine's own schemas hold shipped objects, which only sys.all_objects lists
        val system = ns.schema == "sys" || ns.schema == "INFORMATION_SCHEMA"
        return conn.query(
            """select o.name, o.type, cast(ep.value as nvarchar(4000))
               from ${if (system) "sys.all_objects" else "sys.objects"} o
               join sys.schemas s on s.schema_id = o.schema_id
               left join sys.extended_properties ep on ep.class = 1 and ep.major_id = o.object_id and ep.minor_id = 0 and ep.name = 'MS_Description'
               where s.name = ? ${if (system) "" else "and o.is_ms_shipped = 0 "}and o.type in ('U','V','SO','P','PC','FN','IF','TF','FS','FT')
               order by o.name""",
            ns.schema,
        ) {
            val kind = when (it.getString(2).trim()) {
                "U" -> DbObjectKind.TABLE
                "V" -> DbObjectKind.VIEW
                "SO" -> DbObjectKind.SEQUENCE
                else -> DbObjectKind.ROUTINE
            }
            DbObject(ns, it.getString(1), kind, it.getString(3)?.ifBlank { null })
        }
    }

    /** A column as the catalog has it; [details] and [ddl] both start from this. */
    private class Col(
        val name: String, val type: String, val length: Int, val precision: Int, val scale: Int, val nullable: Boolean,
        val identity: Boolean, val seed: String?, val increment: String?, val default: String?, val computed: String?, val persisted: Boolean,
        val collation: String?,
    )

    private fun columns(conn: Connection, o: DbObject): List<Col> =
        conn.query(
            """select c.name, t.name, c.max_length, c.precision, c.scale, c.is_nullable, c.is_identity,
                      cast(ic.seed_value as nvarchar(40)), cast(ic.increment_value as nvarchar(40)),
                      dc.definition, cc.definition, cc.is_persisted, c.collation_name
               from sys.all_columns c
               join sys.types t on t.user_type_id = c.user_type_id
               left join sys.identity_columns ic on ic.object_id = c.object_id and ic.column_id = c.column_id
               left join sys.default_constraints dc on dc.object_id = c.default_object_id
               left join sys.computed_columns cc on cc.object_id = c.object_id and cc.column_id = c.column_id
               where c.object_id = object_id(?) order by c.column_id""",
            qualified(o),
        ) {
            Col(
                it.getString(1), it.getString(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getBoolean(6), it.getBoolean(7),
                it.getString(8), it.getString(9), it.getString(10), it.getString(11), it.getBoolean(12), it.getString(13),
            )
        }

    /** `nvarchar(100)`, `decimal(10,2)`, `datetime2(3)`, `varbinary(max)`: the type as it is written in DDL. */
    private fun typeText(c: Col): String = when (c.type.lowercase()) {
        "nvarchar", "nchar" -> c.type + "(" + (if (c.length == -1) "max" else (c.length / 2).toString()) + ")"
        "varchar", "char", "varbinary", "binary" -> c.type + "(" + (if (c.length == -1) "max" else c.length.toString()) + ")"
        "decimal", "numeric" -> "${c.type}(${c.precision},${c.scale})"
        "datetime2", "datetimeoffset", "time" -> if (c.scale == 7) c.type else "${c.type}(${c.scale})"
        "float" -> if (c.precision == 53) c.type else "float(${c.precision})"
        else -> c.type
    }

    /** Characters a `char`-like column holds, or null for other types and for `max`. */
    private fun sizeOf(c: Col): Int? = when (c.type.lowercase()) {
        "nvarchar", "nchar" -> c.length.takeIf { it > 0 }?.div(2)
        "varchar", "char", "varbinary", "binary" -> c.length.takeIf { it > 0 }
        "decimal", "numeric" -> c.precision
        else -> null
    }

    private class Index(val name: String, val primary: Boolean, val unique: Boolean, val clustered: Boolean, val keys: List<String>, val included: List<String>)

    private fun indexes(conn: Connection, o: DbObject): List<Index> {
        data class Row(val name: String, val primary: Boolean, val unique: Boolean, val type: String, val column: String, val ord: Int, val included: Boolean)
        val rows = conn.query(
            """select i.name, i.is_primary_key, i.is_unique, i.type_desc, col.name, ic.key_ordinal, ic.is_included_column
               from sys.indexes i
               join sys.index_columns ic on ic.object_id = i.object_id and ic.index_id = i.index_id
               join sys.columns col on col.object_id = ic.object_id and col.column_id = ic.column_id
               where i.object_id = object_id(?) and i.type > 0 and i.name is not null""",
            qualified(o),
        ) { Row(it.getString(1), it.getBoolean(2), it.getBoolean(3), it.getString(4), it.getString(5), it.getInt(6), it.getBoolean(7)) }
        return rows.groupBy { it.name }.map { (name, rs) ->
            Index(
                name, rs.first().primary, rs.first().unique, rs.first().type.startsWith("CLUSTERED"),
                rs.filter { !it.included }.sortedBy { it.ord }.map { it.column }, rs.filter { it.included }.map { it.column },
            )
        }.sortedBy { it.name.lowercase() }
    }

    private class Fk(val name: String, val columns: List<String>, val refNs: Namespace, val refTable: String, val refColumns: List<String>, val onDelete: String, val onUpdate: String)

    private fun foreignKeys(conn: Connection, o: DbObject): List<Fk> {
        data class Row(val name: String, val col: String, val refSchema: String, val refTable: String, val refCol: String, val seq: Int, val onDelete: String, val onUpdate: String)
        val rows = conn.query(
            """select fk.name, pc.name, rs.name, rt.name, rc.name, fkc.constraint_column_id, fk.delete_referential_action_desc, fk.update_referential_action_desc
               from sys.foreign_keys fk
               join sys.foreign_key_columns fkc on fkc.constraint_object_id = fk.object_id
               join sys.columns pc on pc.object_id = fkc.parent_object_id and pc.column_id = fkc.parent_column_id
               join sys.tables rt on rt.object_id = fkc.referenced_object_id
               join sys.schemas rs on rs.schema_id = rt.schema_id
               join sys.columns rc on rc.object_id = fkc.referenced_object_id and rc.column_id = fkc.referenced_column_id
               where fk.parent_object_id = object_id(?)""",
            qualified(o),
        ) { Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6), it.getString(7), it.getString(8)) }
        return rows.groupBy { it.name }.map { (name, rs) ->
            val sorted = rs.sortedBy { it.seq }
            Fk(name, sorted.map { it.col }, Namespace(null, sorted.first().refSchema), sorted.first().refTable, sorted.map { it.refCol }, sorted.first().onDelete, sorted.first().onUpdate)
        }.sortedBy { it.name.lowercase() }
    }

    override fun details(conn: Connection, o: DbObject): TableDetails {
        val cols = columns(conn, o).map {
            ColumnInfo(it.name, it.type, sizeOf(it), it.scale.takeIf { _ -> it.type.lowercase() in setOf("decimal", "numeric") }, it.nullable, it.default, it.identity)
        }
        if (o.kind != DbObjectKind.TABLE) return TableDetails(cols)
        val ix = indexes(conn, o)
        val pk = ix.firstOrNull { it.primary }
        return TableDetails(
            cols, pk?.name, pk?.keys.orEmpty(),
            ix.filter { !it.primary }.map { IndexInfo(it.name, it.unique, it.keys) },
            foreignKeys(conn, o).map { ForeignKeyInfo(it.name, it.columns, it.refNs, it.refTable, it.refColumns) },
        )
    }

    override fun ddl(conn: Connection, o: DbObject): String = when (o.kind) {
        DbObjectKind.TABLE -> tableDdl(conn, o)
        DbObjectKind.SEQUENCE -> sequenceDdl(conn, o)
        else -> conn.query("select m.definition from sys.sql_modules m where m.object_id = object_id(?)", qualified(o)) { it.getString(1) }
            .firstOrNull()?.trim() ?: error("${o.name} no existe o su definición está cifrada")
    }

    private fun tableDdl(conn: Connection, o: DbObject): String {
        val cols = columns(conn, o)
        val ix = indexes(conn, o)
        val lines = cols.map { c ->
            buildString {
                append("    ", quote(c.name), " ")
                if (c.computed != null) {
                    append("as ", c.computed)
                    if (c.persisted) append(" persisted")
                    return@buildString
                }
                append(typeText(c))
                if (c.collation != null && c.type.lowercase() in setOf("varchar", "nvarchar", "char", "nchar", "text", "ntext")) append(" collate ", c.collation)
                if (c.identity) append(" identity(", c.seed ?: "1", ",", c.increment ?: "1", ")")
                append(if (c.nullable) " null" else " not null")
                if (c.default != null) append(" default ", c.default)
            }
        }.toMutableList()
        ix.firstOrNull { it.primary }?.let { pk ->
            lines += "    constraint ${quote(pk.name)} primary key ${if (pk.clustered) "clustered" else "nonclustered"} (${pk.keys.joinToString { quote(it) }})"
        }
        for (fk in foreignKeys(conn, o)) {
            val ref = qualified(DbObject(fk.refNs, fk.refTable, DbObjectKind.TABLE))
            lines += buildString {
                append("    constraint ${quote(fk.name)} foreign key (${fk.columns.joinToString { quote(it) }}) references $ref (${fk.refColumns.joinToString { quote(it) }})")
                if (fk.onDelete != "NO_ACTION") append(" on delete ", fk.onDelete.replace('_', ' ').lowercase())
                if (fk.onUpdate != "NO_ACTION") append(" on update ", fk.onUpdate.replace('_', ' ').lowercase())
            }
        }
        val sb = StringBuilder("create table ${qualified(o)} (\n${lines.joinToString(",\n")}\n);")
        for (i in ix.filter { !it.primary }) {
            sb.append("\n\ncreate ").append(if (i.unique) "unique " else "").append(if (i.clustered) "clustered " else "nonclustered ")
                .append("index ").append(quote(i.name)).append(" on ").append(qualified(o)).append(" (").append(i.keys.joinToString { quote(it) }).append(")")
            if (i.included.isNotEmpty()) sb.append(" include (").append(i.included.joinToString { quote(it) }).append(")")
            sb.append(";")
        }
        return sb.toString()
    }

    private fun sequenceDdl(conn: Connection, o: DbObject): String =
        conn.query(
            """select t.name, cast(s.start_value as nvarchar(40)), cast(s.increment as nvarchar(40)), cast(s.minimum_value as nvarchar(40)),
                      cast(s.maximum_value as nvarchar(40)), s.is_cycling
               from sys.sequences s join sys.types t on t.user_type_id = s.user_type_id where s.object_id = object_id(?)""",
            qualified(o),
        ) {
            "create sequence ${qualified(o)} as ${it.getString(1)} start with ${it.getString(2)} increment by ${it.getString(3)} " +
                "minvalue ${it.getString(4)} maxvalue ${it.getString(5)} ${if (it.getBoolean(6)) "cycle" else "no cycle"};"
        }.firstOrNull() ?: error("${o.name} no existe")
}
