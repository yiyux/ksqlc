package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection
import java.sql.ResultSet

object PostgresDialect : MetaDialect() {
    override fun namespaces(conn: Connection, includeSystem: Boolean): List<Namespace> =
        conn.query(
            if (includeSystem) "select nspname from pg_namespace where nspname !~ '^pg_(toast|temp_|toast_temp_)' order by 1"
            else "select nspname from pg_namespace where nspname !~ '^pg_' and nspname <> 'information_schema' order by 1",
        ) {
            Namespace(null, it.getString(1))
        }

    override fun objects(conn: Connection, ns: Namespace, includeSystem: Boolean): List<DbObject> {
        val relations = conn.query(
            """select c.relname, c.relkind, obj_description(c.oid, 'pg_class')
               from pg_class c join pg_namespace n on n.oid = c.relnamespace
               where n.nspname = ? and c.relkind in ('r','p','f','v','m','S') order by c.relname""",
            ns.schema,
        ) {
            val kind = when (it.getString(2)) {
                "v" -> DbObjectKind.VIEW
                "m" -> DbObjectKind.MATERIALIZED_VIEW
                "S" -> DbObjectKind.SEQUENCE
                else -> DbObjectKind.TABLE
            }
            DbObject(ns, it.getString(1), kind, it.getString(3)?.ifBlank { null })
        }
        // Overloads are told apart by their identity arguments, which is also what the DDL lookup matches on.
        val routines = conn.query(
            """select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')', obj_description(p.oid, 'pg_proc')
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
               where n.nspname = ? and p.prokind in ('f','p') order by 1""",
            ns.schema,
        ) { DbObject(ns, it.getString(1), DbObjectKind.ROUTINE, it.getString(2)?.ifBlank { null }) }
        return relations + routines
    }

    override fun call(conn: Connection, o: DbObject): List<String> {
        val name = o.name.substringBefore('(')
        // the name carries the identity arguments, which tell overloads apart (see [objects])
        val row = conn.query(
            """select p.prokind, p.proargnames::text[], p.proargmodes::text[], p.pronargs from pg_proc p join pg_namespace n on n.oid = p.pronamespace
               where n.nspname = ? and p.prokind in ('f','p') and p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' = ?""",
            o.ns.schema, o.name,
        ) { rs -> Triple(rs.getString(1), rs.getArray(2)?.array as Array<*>?, rs.getArray(3)?.array as Array<*>?) to rs.getInt(4) }.firstOrNull() ?: error("${o.name} no existe")
        val (kind, argNames, argModes) = row.first
        val count = argModes?.size ?: row.second
        // 'o' is an OUT argument (a procedure still wants a slot for it), 't' a column of the result table
        fun modeOf(i: Int) = argModes?.get(i) as String? ?: "i"
        val inputs = (0 until count).filter { modeOf(it) in setOf("i", "b", "v") }
        val holders = placeholders(inputs.map { argNames?.getOrNull(it) as String? }).iterator()
        val q = "${quote(o.ns.schema!!)}.${quote(name)}"
        return if (kind == "p") listOf("call $q(" + (0 until count).joinToString(", ") { if (modeOf(it) == "o") "null" else holders.next() } + ")")
        else listOf("select * from $q(" + inputs.joinToString(", ") { holders.next() } + ")")
    }

    override fun tableModel(conn: Connection, o: DbObject): TableModel {
        val (oid, tableComment) = conn.query(
            """select c.oid::bigint, obj_description(c.oid, 'pg_class') from pg_class c join pg_namespace n on n.oid = c.relnamespace
               where n.nspname = ? and c.relname = ? and c.relkind in ('r', 'p')""",
            o.ns.schema, o.name,
        ) { it.getLong(1) to it.getString(2) }.firstOrNull() ?: error("${o.name} no existe")

        val columns = conn.query(
            """select a.attname::text, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid), a.attgenerated::text,
                      col_description(a.attrelid, a.attnum), a.attidentity::text, s.seqmin, s.seqmax, s.seqincrement, s.seqcache, s.seqcycle
               from pg_attribute a
               left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
               left join pg_depend dep on a.attidentity <> '' and dep.refclassid = 'pg_class'::regclass and dep.refobjid = a.attrelid
                    and dep.refobjsubid = a.attnum and dep.classid = 'pg_class'::regclass and dep.deptype = 'i'
               left join pg_sequence s on s.seqrelid = dep.objid
               where a.attrelid = ?::oid and a.attnum > 0 and not a.attisdropped order by a.attnum""",
            oid,
        ) { rs ->
            val expr = rs.getString(4)
            val generated = rs.getString(5).isNotEmpty()
            val identity = rs.getString(7).takeIf { it.isNotEmpty() }?.let { mode ->
                Identity(always = mode == "a", min = rs.getString(8), max = rs.getString(9), step = rs.getString(10), cache = rs.getString(11), cycle = rs.getBoolean(12))
            }
            ColumnModel(rs.getString(1), rs.getString(1), rs.getString(2), rs.getBoolean(3), expr.takeIf { !generated }, rs.getString(6), identity, expr.takeIf { generated })
        }

        val keys = conn.query(
            """select con.conname::text, con.contype::text, con.condeferrable, con.condeferred,
                      array(select a.attname::text from unnest(con.conkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.num order by k.ord)
               from pg_constraint con where con.conrelid = ?::oid and con.contype in ('p', 'u') order by con.contype <> 'p', con.conname""",
            oid,
        ) { rs -> KeyModel(rs.getString(1), rs.getString(1), rs.getString(2) == "p", rs.names(5), rs.getBoolean(3), rs.getBoolean(4)) }

        val actions = mapOf("a" to "NO ACTION", "r" to "RESTRICT", "c" to "CASCADE", "n" to "SET NULL", "d" to "SET DEFAULT")
        val foreignKeys = conn.query(
            """select con.conname::text, con.confdeltype::text, con.confupdtype::text, con.condeferrable, con.condeferred, n.nspname::text, c.relname::text,
                      array(select a.attname::text from unnest(con.conkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.num order by k.ord),
                      array(select a.attname::text from unnest(con.confkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.confrelid and a.attnum = k.num order by k.ord)
               from pg_constraint con join pg_class c on c.oid = con.confrelid join pg_namespace n on n.oid = c.relnamespace
               where con.conrelid = ?::oid and con.contype = 'f' order by con.conname""",
            oid,
        ) { rs ->
            ForeignKeyModel(rs.getString(1), rs.getString(1), rs.names(8), Namespace(null, rs.getString(6)), rs.getString(7), rs.names(9),
                actions.getValue(rs.getString(2)), actions.getValue(rs.getString(3)), rs.getBoolean(4), rs.getBoolean(5))
        }

        val checks = conn.query(
            "select conname::text, pg_get_expr(conbin, conrelid) from pg_constraint where conrelid = ?::oid and contype = 'c' order by conname", oid,
        ) { CheckModel(it.getString(1), it.getString(1), it.getString(2)) }

        class IndexRow(val name: String, val unique: Boolean, val method: String, val keyCount: Int, val condition: String?, val n: Int,
            val def: String, val expression: Boolean, val desc: Boolean, val collation: String?, val opClass: String?)
        val rows = conn.query(
            """select ic.relname::text, i.indisunique, am.amname::text, i.indnkeyatts::int, pg_get_expr(i.indpred, i.indrelid), k.n,
                      pg_get_indexdef(i.indexrelid, k.n, true), i.indkey[k.n - 1] = 0, (i.indoption[k.n - 1] & 1) <> 0,
                      case when i.indcollation[k.n - 1] not in (0, 100) then (select collname::text from pg_collation where oid = i.indcollation[k.n - 1]) end,
                      case when not opc.opcdefault then opc.opcname::text end
               from pg_index i
               join pg_class ic on ic.oid = i.indexrelid
               join pg_am am on am.oid = ic.relam
               cross join generate_series(1, i.indnatts::int) k(n)
               left join pg_opclass opc on opc.oid = i.indclass[k.n - 1]
               where i.indrelid = ?::oid
                 and not exists (select 1 from pg_constraint con where con.conindid = i.indexrelid and con.conrelid = i.indrelid and con.contype in ('p', 'u', 'x'))
               order by ic.relname, k.n""",
            oid,
        ) { rs -> IndexRow(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9), rs.getString(10), rs.getString(11)) }
        fun unquote(s: String) = if (s.startsWith("\"")) s.substring(1, s.length - 1).replace("\"\"", "\"") else s
        val indexes = rows.groupBy { it.name }.map { (name, parts) ->
            val first = parts.first()
            IndexModel(name, name, first.unique, first.method,
                parts.filter { it.n <= first.keyCount }.map { IndexColumn(if (it.expression) it.def else unquote(it.def), it.expression, if (it.desc) "DESC" else "", it.collation, it.opClass) },
                parts.filter { it.n > first.keyCount }.map { unquote(it.def) }, first.condition)
        }

        return TableModel(o.name, tableComment, columns, keys, foreignKeys, indexes, checks)
    }

    private fun ResultSet.names(i: Int): List<String> = (getArray(i).array as Array<*>).map { it.toString() }

    override fun ddl(conn: Connection, o: DbObject): String = when (o.kind) {
        DbObjectKind.TABLE, DbObjectKind.SEQUENCE -> generateDdl(o, details(conn, o))
        DbObjectKind.VIEW, DbObjectKind.MATERIALIZED_VIEW -> {
            val def = conn.query(
                """select pg_get_viewdef(c.oid, true) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                   where n.nspname = ? and c.relname = ?""",
                o.ns.schema, o.name,
            ) { it.getString(1) }.firstOrNull()?.trim() ?: error("${o.name} no existe")
            val head = if (o.kind == DbObjectKind.VIEW) "create or replace view" else "create materialized view"
            "$head ${qualified(o)} as\n$def"
        }
        DbObjectKind.ROUTINE -> conn.query(
            """select pg_get_functiondef(p.oid) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
               where n.nspname = ? and p.prokind in ('f','p')
                 and p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' = ?""",
            o.ns.schema, o.name,
        ) { it.getString(1) }.firstOrNull() ?: error("${o.name} no existe")
    }
}
