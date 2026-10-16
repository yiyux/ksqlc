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

    override fun call(conn: Connection, o: DbObject): List<String> {
        val type = conn.query("select o.type from sys.objects o join sys.schemas s on s.schema_id = o.schema_id where s.name = ? and o.name = ?", o.ns.schema, o.name) { it.getString(1).trim() }.firstOrNull() ?: error("${o.name} no existe")
        val params = conn.query("select name, is_output from sys.parameters where object_id = object_id(?) and parameter_id > 0 order by parameter_id", qualified(o)) { it.getString(1) to it.getBoolean(2) }
        if (params.any { it.second }) error("tiene parámetros de salida y todavía no se pueden pedir")
        val holders = placeholders(params.map { it.first })
        val q = qualified(o)
        return listOf(when (type) {
            "FN", "FS" -> "select $q(${holders.joinToString(", ")}) as ${quote(o.name)}"
            "IF", "TF", "FT" -> "select * from $q(${holders.joinToString(", ")})"
            else -> "exec $q" + params.indices.joinToString(", ", prefix = if (params.isEmpty()) "" else " ") { "${params[it].first} = ${holders[it]}" }
        })
    }

    /** A column as the catalog has it; [details] and [ddl] both start from this. */
    internal class Col(
        val name: String, val type: String, val length: Int, val precision: Int, val scale: Int, val nullable: Boolean,
        val identity: Boolean, val seed: String?, val increment: String?, val default: String?, val computed: String?, val persisted: Boolean,
        val collation: String?,
    )

    internal fun columns(conn: Connection, o: DbObject): List<Col> =
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
    internal fun typeText(c: Col): String = when (c.type.lowercase()) {
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

    internal class Fk(val name: String, val columns: List<String>, val refNs: Namespace, val refTable: String, val refColumns: List<String>, val onDelete: String, val onUpdate: String)

    internal fun foreignKeys(conn: Connection, o: DbObject): List<Fk> {
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

    override val modifyCaps = ModifyCaps(
        prelude = listOf("set lock_timeout 5000"), deferrable = false, indexColumnOptions = false, identityOptions = false,
        singleIdentity = true, identityOnCreateOnly = true, virtualColumns = true, // virtual = not persisted
        indexMethods = listOf("clustered", "nonclustered"), fkActions = listOf("NO ACTION", "CASCADE", "SET NULL", "SET DEFAULT"),
    )

    override fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = sqlServerAlterTable(this, o, before, after)

    override fun tableModel(conn: Connection, o: DbObject): TableModel {
        val (temporal, inMemory, comment) = conn.query(
            """select t.temporal_type, t.is_memory_optimized, cast(ep.value as nvarchar(4000))
               from sys.tables t
               left join sys.extended_properties ep on ep.class = 1 and ep.major_id = t.object_id and ep.minor_id = 0 and ep.name = 'MS_Description'
               where t.object_id = object_id(?)""",
            qualified(o),
        ) { Triple(it.getInt(1), it.getBoolean(2), it.getString(3)) }.firstOrNull() ?: error("${o.name} no existe")
        if (temporal != 0) error("${o.name} es una tabla temporal (system-versioned) y todavía no se puede modificar")
        if (inMemory) error("${o.name} está optimizada para memoria y todavía no se puede modificar")

        val dbCollation = conn.query("select convert(nvarchar(128), databasepropertyex(db_name(), 'Collation'))") { it.getString(1) }.firstOrNull()
        val comments = conn.query(
            """select c.name, cast(ep.value as nvarchar(4000)) from sys.columns c
               join sys.extended_properties ep on ep.class = 1 and ep.major_id = c.object_id and ep.minor_id = c.column_id and ep.name = 'MS_Description'
               where c.object_id = object_id(?)""",
            qualified(o),
        ) { it.getString(1) to it.getString(2) }.toMap()
        val columnModels = columns(conn, o).map { c ->
            // alter column without a collation resets it to the database's, so a column's own travels in its type
            val collate = c.collation?.takeIf { c.computed == null && it != dbCollation && c.type.lowercase() in setOf("varchar", "nvarchar", "char", "nchar") }
            ColumnModel(
                c.name, c.name, typeText(c) + (collate?.let { " collate $it" } ?: ""),
                notNull = c.computed == null && !c.nullable, default = c.default?.let(::unwrap), comment = comments[c.name]?.ifBlank { null },
                identity = if (c.identity) Identity() else null,
                generated = c.computed?.let(::unwrap), generatedStored = c.persisted,
            )
        }

        // one row per column of every index; the types it can hold are rowstore clustered (1) and nonclustered (2), and the rest are left alone
        data class Part(val name: String, val primary: Boolean, val uniqueConstraint: Boolean, val unique: Boolean, val type: Int, val filter: String?, val column: String, val ord: Int, val desc: Boolean, val included: Boolean)
        val parts = conn.query(
            """select i.name, i.is_primary_key, i.is_unique_constraint, i.is_unique, i.type, i.filter_definition, col.name, ic.key_ordinal, ic.is_descending_key, ic.is_included_column
               from sys.indexes i
               join sys.index_columns ic on ic.object_id = i.object_id and ic.index_id = i.index_id
               join sys.columns col on col.object_id = ic.object_id and col.column_id = ic.column_id
               where i.object_id = object_id(?) and i.type > 0 and i.name is not null""",
            qualified(o),
        ) { Part(it.getString(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4), it.getInt(5), it.getString(6), it.getString(7), it.getInt(8), it.getBoolean(9), it.getBoolean(10)) }
            .groupBy { it.name }
        val allNames = conn.query("select name from sys.indexes where object_id = object_id(?) and type > 0 and name is not null", qualified(o)) { it.getString(1) }
        fun Map.Entry<String, List<Part>>.modeled() = value.first().type in 1..2
        val keys = parts.filter { it.modeled() && (it.value.first().primary || it.value.first().uniqueConstraint) }.filterValues { ps -> ps.none { it.desc } }.map { (name, ps) ->
            KeyModel(name, name, ps.first().primary, ps.filter { !it.included }.sortedBy { it.ord }.map { it.column }, clustered = ps.first().type == 1)
        }.sortedWith(compareBy({ !it.primary }, { it.name.lowercase() }))
        val indexes = parts.filter { it.modeled() && !it.value.first().primary && !it.value.first().uniqueConstraint }.map { (name, ps) ->
            val first = ps.first()
            IndexModel(
                name, name, first.unique, if (first.type == 1) "clustered" else "nonclustered",
                ps.filter { !it.included }.sortedBy { it.ord }.map { IndexColumn(it.column, order = if (it.desc) "DESC" else "") },
                ps.filter { it.included }.map { it.column }, first.filter,
            )
        }.sortedBy { it.name.lowercase() }
        // columnstore, xml, spatial and hash indexes, and keys with a descending column, are not modeled: leaving them out means they are never dropped or recreated
        val skipped = (allNames - keys.map { it.name }.toSet() - indexes.map { it.name }.toSet()).sortedBy { it.lowercase() }

        val foreignKeys = foreignKeys(conn, o).map {
            ForeignKeyModel(it.name, it.name, it.columns, it.refNs, it.refTable, it.refColumns, it.onDelete.replace('_', ' '), it.onUpdate.replace('_', ' '))
        }
        val checks = conn.query("select name, definition from sys.check_constraints where parent_object_id = object_id(?) order by name", qualified(o)) {
            CheckModel(it.getString(1), it.getString(1), unwrap(it.getString(2)))
        }
        return TableModel(o.name, comment?.ifBlank { null }, columnModels, keys, foreignKeys, indexes, checks, skipped)
    }

    /**
     * The catalog gives defaults, checks and computed columns wrapped in one pair of parentheses (`((0))`, `([a]>(0))`); the model
     * holds the text without it and the generator puts it back, so reading and writing back is the identity.
     */
    internal fun unwrap(s: String): String {
        val t = s.trim()
        if (t.length < 2 || t.first() != '(' || t.last() != ')') return t
        var depth = 0; var i = 0
        while (i < t.length) {
            when (t[i]) {
                '\'' -> { i++; while (i < t.length && !(t[i] == '\'' && t.getOrNull(i + 1) != '\'')) i += if (t[i] == '\'') 2 else 1 }
                '[' -> { i++; while (i < t.length && !(t[i] == ']' && t.getOrNull(i + 1) != ']')) i += if (t[i] == ']') 2 else 1 }
                '(' -> depth++
                ')' -> { depth--; if (depth == 0 && i != t.lastIndex) return t }
            }
            i++
        }
        return t.substring(1, t.length - 1).trim()
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
