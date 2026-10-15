package ksqlc.drivers

import ksqlc.core.*
import kotlin.test.*

class AlterationsTest {
    private fun obj(kind: DbObjectKind, ns: Namespace = Namespace(null, "app")) = DbObject(ns, "t", kind)
    private val table = obj(DbObjectKind.TABLE)

    @Test fun dropByKindAndQuotingPerEngine() {
        assertEquals("drop table \"app\".\"t\"", dropSql(DbKind.POSTGRES, table))
        assertEquals("drop view \"app\".\"t\"", dropSql(DbKind.POSTGRES, obj(DbObjectKind.VIEW)))
        assertEquals("drop materialized view \"app\".\"t\"", dropSql(DbKind.POSTGRES, obj(DbObjectKind.MATERIALIZED_VIEW)))
        assertEquals("drop sequence \"app\".\"t\"", dropSql(DbKind.POSTGRES, obj(DbObjectKind.SEQUENCE)))
        assertEquals("drop table `app`.`t`", dropSql(DbKind.MYSQL, DbObject(Namespace("app", null), "t", DbObjectKind.TABLE)))
        assertEquals("drop table [app].[t]", dropSql(DbKind.MSSQL, table))
        assertNull(dropSql(DbKind.POSTGRES, obj(DbObjectKind.ROUTINE)))
    }

    @Test fun truncateIsADeleteInSqliteAndOnlyForTables() {
        assertEquals("truncate table \"app\".\"t\"", truncateSql(DbKind.POSTGRES, table))
        assertEquals("delete from \"app\".\"t\"", truncateSql(DbKind.SQLITE, table))
        assertNull(truncateSql(DbKind.POSTGRES, obj(DbObjectKind.VIEW)))
    }

    @Test fun refreshMaterializedView() {
        val mv = obj(DbObjectKind.MATERIALIZED_VIEW)
        assertEquals("refresh materialized view \"app\".\"t\"", refreshMatViewSql(DbKind.POSTGRES, mv))
        assertEquals("begin dbms_mview.refresh('app.t'); end;", refreshMatViewSql(DbKind.ORACLE, mv))
        assertNull(refreshMatViewSql(DbKind.MYSQL, mv))
        assertNull(refreshMatViewSql(DbKind.POSTGRES, table))
    }

    @Test fun dropIndexNeedsTheTableInMySqlAndSqlServer() {
        assertEquals("drop index \"app\".\"ix\"", dropIndexSql(DbKind.POSTGRES, table, "ix"))
        assertEquals("drop index `ix` on `app`.`t`", dropIndexSql(DbKind.MARIADB, DbObject(Namespace("app", null), "t", DbObjectKind.TABLE), "ix"))
        assertEquals("drop index [ix] on [app].[t]", dropIndexSql(DbKind.MSSQL, table, "ix"))
    }

    @Test fun dropForeignKey() {
        assertEquals("alter table \"app\".\"t\" drop constraint \"fk\"", dropForeignKeySql(DbKind.POSTGRES, table, "fk"))
        assertEquals("alter table `app`.`t` drop foreign key `fk`", dropForeignKeySql(DbKind.MYSQL, DbObject(Namespace("app", null), "t", DbObjectKind.TABLE), "fk"))
        assertNull(dropForeignKeySql(DbKind.SQLITE, table, "fk"))
        assertNull(dropForeignKeySql(DbKind.CASSANDRA, table, "fk"))
    }
}
