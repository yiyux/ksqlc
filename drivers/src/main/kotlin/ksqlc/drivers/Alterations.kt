package ksqlc.drivers

import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.drivers.dialect.Dialect

/*
 * The statements behind the explorer's alterations. Each returns null when the engine has no such statement, so the menu
 * simply leaves the entry out. Objects only come from the engines that have them (no materialized views in SQLite), so the
 * per-kind statements don't check that again.
 */

fun dropSql(kind: DbKind, o: DbObject): String? {
    val what = when (o.kind) {
        DbObjectKind.TABLE -> "table"
        DbObjectKind.VIEW -> "view"
        DbObjectKind.MATERIALIZED_VIEW -> "materialized view"
        DbObjectKind.SEQUENCE -> "sequence"
        DbObjectKind.ROUTINE -> return null     // shortcut: PostgreSQL needs the signature and function vs procedure, not worth it yet
    }
    return "drop $what ${Dialect.of(kind).qualified(o)}"
}

fun truncateSql(kind: DbKind, o: DbObject): String? {
    if (o.kind != DbObjectKind.TABLE) return null
    val q = Dialect.of(kind).qualified(o)
    return if (kind == DbKind.SQLITE) "delete from $q" else "truncate table $q"
}

fun refreshMatViewSql(kind: DbKind, o: DbObject): String? {
    if (o.kind != DbObjectKind.MATERIALIZED_VIEW) return null
    return when (kind) {
        DbKind.POSTGRES -> "refresh materialized view ${Dialect.of(kind).qualified(o)}"
        DbKind.ORACLE -> "begin dbms_mview.refresh('${(o.ns.schema + "." + o.name).replace("'", "''")}'); end;"
        else -> null
    }
}

/** [table] is the table the index belongs to; PostgreSQL, Oracle, SQLite and Cassandra name an index on its own, MySQL-family and SQL Server also need the table. */
fun dropIndexSql(kind: DbKind, table: DbObject, index: String): String {
    val d = Dialect.of(kind)
    return when (kind) {
        DbKind.MYSQL, DbKind.MARIADB, DbKind.MSSQL -> "drop index ${d.quote(index)} on ${d.qualified(table)}"
        else -> "drop index ${d.qualified(table.copy(name = index))}"
    }
}

fun dropForeignKeySql(kind: DbKind, table: DbObject, fk: String): String? {
    val d = Dialect.of(kind)
    val t = d.qualified(table)
    return when (kind) {
        DbKind.SQLITE, DbKind.CASSANDRA -> null
        DbKind.MYSQL, DbKind.MARIADB -> "alter table $t drop foreign key ${d.quote(fk)}"
        else -> "alter table $t drop constraint ${d.quote(fk)}"
    }
}
