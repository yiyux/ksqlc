package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection
import java.sql.SQLException

/**
 * Oracle through its data dictionary (`ALL_*` views): user-created schemas, tables, views, materialized views, sequences and
 * procedures/functions with their comments, identity and virtual columns, constraints and indexes, and the exact `CREATE`
 * text from `DBMS_METADATA`. Everything is read with the connected user's privileges, so another schema's DDL needs
 * `SELECT_CATALOG_ROLE` (or ownership).
 */
object OracleDialect : MetaDialect() {
    override fun emptyInsert(table: String, firstColumn: String) = "insert into $table ($firstColumn) values (default)"

    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.query("select username from all_users" + (if (includeSystem) "" else " where oracle_maintained = 'N'") + " order by username") { Namespace(null, it.getString(1)) }

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        val owner = ns.schema
        fun kind(sql: String, kind: DbObjectKind) = conn.query(sql, owner) { DbObject(ns, it.getString(1), kind, it.getString(2)?.ifBlank { null }) }
        val tables = kind(
            """select t.table_name, c.comments from all_tables t
               left join all_tab_comments c on c.owner = t.owner and c.table_name = t.table_name and c.table_type = 'TABLE'
               where t.owner = ? and t.table_name not like 'BIN$%' and t.nested = 'NO' and t.secondary = 'N' and t.iot_name is null
                 and not exists (select 1 from all_mviews m where m.owner = t.owner and m.mview_name = t.table_name)""",
            DbObjectKind.TABLE,
        )
        val views = kind(
            """select v.view_name, c.comments from all_views v
               left join all_tab_comments c on c.owner = v.owner and c.table_name = v.view_name and c.table_type = 'VIEW'
               where v.owner = ?""",
            DbObjectKind.VIEW,
        )
        val mviews = kind(
            """select m.mview_name, c.comments from all_mviews m
               left join all_tab_comments c on c.owner = m.owner and c.table_name = m.mview_name
               where m.owner = ?""",
            DbObjectKind.MATERIALIZED_VIEW,
        )
        val sequences = kind(
            // the sequences behind identity columns are implementation detail (ISEQ$$_nnn)
            "select sequence_name, cast(null as varchar2(10)) from all_sequences where sequence_owner = ? and sequence_name not like 'ISEQ$$\\_%' escape '\\'",
            DbObjectKind.SEQUENCE,
        )
        val routines = kind("select object_name, cast(null as varchar2(10)) from all_objects where owner = ? and object_type in ('PROCEDURE', 'FUNCTION')", DbObjectKind.ROUTINE)
        return (tables + views + mviews + sequences + routines).sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
    }

    override fun details(conn: Connection, o: DbObject): TableDetails {
        val owner = o.ns.schema
        // DATA_DEFAULT is a LONG: Oracle only lets it be read first, in column order
        val cols = conn.query(
            """select data_default, column_name, data_type, char_length, data_precision, data_scale, nullable, identity_column, virtual_column, char_used
               from all_tab_cols where owner = ? and table_name = ? and hidden_column = 'NO' order by internal_column_id""",
            owner, o.name,
        ) {
            val default = it.getString(1)?.trim()?.ifBlank { null }
            val type = it.getString(3)
            val charLength = it.getInt(4).takeUnless { _ -> it.wasNull() }
            val precision = it.getInt(5).takeUnless { _ -> it.wasNull() }
            val scale = it.getInt(6).takeUnless { _ -> it.wasNull() }
            val textual = type in setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR")
            ColumnInfo(
                it.getString(2), type, if (textual) charLength?.takeIf { n -> n > 0 } else precision, if (textual) null else scale,
                it.getString(7) == "Y", default, it.getString(8) == "YES",
            )
        }
        if (o.kind != DbObjectKind.TABLE) return TableDetails(cols)

        data class Pk(val name: String, val col: String, val pos: Int)
        val pk = conn.query(
            """select c.constraint_name, cc.column_name, cc.position from all_constraints c
               join all_cons_columns cc on cc.owner = c.owner and cc.constraint_name = c.constraint_name
               where c.owner = ? and c.table_name = ? and c.constraint_type = 'P' order by cc.position""",
            owner, o.name,
        ) { Pk(it.getString(1), it.getString(2), it.getInt(3)) }

        data class Ix(val name: String, val unique: Boolean, val col: String, val pos: Int)
        val indexes = conn.query(
            """select i.index_name, i.uniqueness, ic.column_name, ic.column_position from all_indexes i
               join all_ind_columns ic on ic.index_owner = i.owner and ic.index_name = i.index_name
               where i.table_owner = ? and i.table_name = ? and i.index_type <> 'LOB'
                 and not exists (select 1 from all_constraints k where k.owner = i.owner and k.index_name = i.index_name and k.constraint_type in ('P', 'U'))""",
            owner, o.name,
        ) { Ix(it.getString(1), it.getString(2) == "UNIQUE", it.getString(3), it.getInt(4)) }
            .groupBy { it.name }.map { (n, r) -> IndexInfo(n, r.first().unique, r.sortedBy { it.pos }.map { it.col }) }.sortedBy { it.name.lowercase() }

        data class Fk(val name: String, val col: String, val pos: Int, val refOwner: String, val refTable: String, val refCol: String)
        val fks = conn.query(
            """select c.constraint_name, cc.column_name, cc.position, r.owner, r.table_name, rc.column_name
               from all_constraints c
               join all_cons_columns cc on cc.owner = c.owner and cc.constraint_name = c.constraint_name
               join all_constraints r on r.owner = c.r_owner and r.constraint_name = c.r_constraint_name
               join all_cons_columns rc on rc.owner = r.owner and rc.constraint_name = r.constraint_name and rc.position = cc.position
               where c.owner = ? and c.table_name = ? and c.constraint_type = 'R'""",
            owner, o.name,
        ) { Fk(it.getString(1), it.getString(2), it.getInt(3), it.getString(4), it.getString(5), it.getString(6)) }
            .groupBy { it.name }.map { (n, r) ->
                val s = r.sortedBy { it.pos }
                ForeignKeyInfo(n, s.map { it.col }, Namespace(null, s.first().refOwner), s.first().refTable, s.map { it.refCol })
            }.sortedBy { it.name?.lowercase().orEmpty() }

        // Unique constraints also appear as unique indexes in other engines; here they are constraints, so list their columns too.
        val uniques = conn.query(
            """select c.constraint_name, cc.column_name, cc.position from all_constraints c
               join all_cons_columns cc on cc.owner = c.owner and cc.constraint_name = c.constraint_name
               where c.owner = ? and c.table_name = ? and c.constraint_type = 'U'""",
            owner, o.name,
        ) { Pk(it.getString(1), it.getString(2), it.getInt(3)) }
            .groupBy { it.name }.map { (n, r) -> IndexInfo(n, true, r.sortedBy { it.pos }.map { it.col }) }

        return TableDetails(cols, pk.firstOrNull()?.name, pk.map { it.col }, (indexes + uniques).sortedBy { it.name.lowercase() }, fks)
    }

    override fun call(conn: Connection, o: DbObject): List<String> {
        val type = conn.query("select object_type from all_objects where owner = ? and object_name = ? and object_type in ('PROCEDURE','FUNCTION')", o.ns.schema, o.name) { it.getString(1) }.firstOrNull() ?: error("${o.name} no existe")
        // position 0 is a function's return value
        val params = conn.query("select argument_name, in_out from all_arguments where owner = ? and object_name = ? and package_name is null and data_level = 0 and position > 0 order by position", o.ns.schema, o.name) { it.getString(1) to it.getString(2) }
        if (params.any { it.second != "IN" }) error("tiene parámetros de salida y todavía no se pueden pedir")
        val args = placeholders(params.map { it.first }).joinToString(", ")
        return listOf(if (type == "FUNCTION") "select ${qualified(o)}($args) as ${quote(o.name)} from dual" else "begin ${qualified(o)}($args); end;")
    }

    override fun ddl(conn: Connection, o: DbObject): String {
        val type = when (o.kind) {
            DbObjectKind.TABLE -> "TABLE"
            DbObjectKind.VIEW -> "VIEW"
            DbObjectKind.MATERIALIZED_VIEW -> "MATERIALIZED_VIEW"
            DbObjectKind.SEQUENCE -> "SEQUENCE"
            DbObjectKind.ROUTINE -> conn.query("select object_type from all_objects where owner = ? and object_name = ? and object_type in ('PROCEDURE','FUNCTION')", o.ns.schema, o.name) { it.getString(1) }.firstOrNull() ?: error("${o.name} no existe")
        }
        // readable text: no storage or tablespace clauses, statements ended with ';'
        conn.createStatement().use { st ->
            st.execute(
                """begin
                     dbms_metadata.set_transform_param(dbms_metadata.session_transform, 'SEGMENT_ATTRIBUTES', false);
                     dbms_metadata.set_transform_param(dbms_metadata.session_transform, 'STORAGE', false);
                     dbms_metadata.set_transform_param(dbms_metadata.session_transform, 'TABLESPACE', false);
                     dbms_metadata.set_transform_param(dbms_metadata.session_transform, 'SQLTERMINATOR', true);
                   end;""",
            )
        }
        fun get(type: String, name: String) = conn.query("select dbms_metadata.get_ddl(?, ?, ?) from dual", type, name, o.ns.schema) { it.getString(1) }.firstOrNull()?.trim()
        val own = get(type, o.name) ?: error("${o.name} no existe")
        if (o.kind != DbObjectKind.TABLE) return own
        val extra = details(conn, o).indexes.filter { ix -> !conn.query("select 1 from all_constraints where owner = ? and constraint_name = ?", o.ns.schema, ix.name) { 1 }.any() }
            .mapNotNull { ix -> try { get("INDEX", ix.name) } catch (e: SQLException) { null } }
        return (listOf(own) + extra).joinToString("\n\n")
    }
}
