package ksqlc.drivers.dialect

import ksqlc.core.*
import java.sql.Connection

object PostgresDialect : MetaDialect() {
    override fun namespaces(conn: Connection): List<Namespace> =
        conn.query("select nspname from pg_namespace where nspname !~ '^pg_' and nspname <> 'information_schema' order by 1") {
            Namespace(null, it.getString(1))
        }

    override fun objects(conn: Connection, ns: Namespace): List<DbObject> {
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
