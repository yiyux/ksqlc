package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AlterationsMySqlServerTest {
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, listOf(
                "create database alt",
                "create table alt.parent(id int primary key)",
                "create table alt.child(id int primary key, parent_id int, index child_parent_idx(parent_id), constraint child_parent_fk foreign key (parent_id) references alt.parent(id))",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    @Test fun foreignKeyAndIndexGoAwayAndTheTableTruncates() {
        val child = DbObject(Namespace("alt", null), "child", DbObjectKind.TABLE)
        fun run(sql: String?) = Session(fx.ds, fx.password).use { s ->
            val r = s.runScript(listOf(assertNotNull(sql))).single()
            assertFalse(r is StatementResult.Failed, (r as? StatementResult.Failed)?.message)
        }
        run(dropForeignKeySql(DbKind.MYSQL, child, "child_parent_fk"))
        run(dropIndexSql(DbKind.MYSQL, child, "child_parent_idx"))
        run(truncateSql(DbKind.MYSQL, child))
        Introspector(fx.ds, fx.password).use { i ->
            val d = i.details(child)
            assertTrue(d.foreignKeys.isEmpty()); assertTrue(d.indexes.none { it.name == "child_parent_idx" })
        }
    }
}
