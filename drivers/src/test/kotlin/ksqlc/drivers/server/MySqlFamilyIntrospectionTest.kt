package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.Session
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

private val SETUP = listOf(
    "create database app",
    "create table app.customers(id int auto_increment primary key, email varchar(120) not null, note text, unique key uq_email(email))",
    "create table app.orders(id int primary key, customer_id int not null, total decimal(10,2) default 0, " +
        "index orders_customer_idx(customer_id), constraint fk_orders_customer foreign key (customer_id) references app.customers(id))",
    "create view app.big_orders as select * from app.orders where total > 100",
    "create function app.add2(a int, b int) returns int deterministic return a+b",
    "create table app.user_roles(id int)",
    "create table app.userXroles(id int, extra int)",
)

/** Same expectations for MySQL and MariaDB: both are handled by [ksqlc.drivers.dialect.MySqlDialect]. */
abstract class MySqlFamilyBase {
    protected abstract val fx: ServerFixture
    private val app = Namespace("app", null)
    private fun intro() = Introspector(fx.ds, fx.password)

    @Test fun namespacesAreDatabasesWithoutSystemOnes() {
        intro().use { i ->
            val names = i.namespaces().map { it.label }
            assertTrue("app" in names, names.toString())
            assertTrue(names.none { it in setOf("mysql", "sys", "performance_schema", "information_schema") }, names.toString())
            assertTrue(i.namespaces().all { it.schema == null })
        }
    }

    @Test fun objectsHaveKindsAndRoutines() {
        intro().use { i ->
            val byName = i.objects(app).associateBy { it.name }
            assertEquals(DbObjectKind.TABLE, byName.getValue("customers").kind)
            assertEquals(DbObjectKind.VIEW, byName.getValue("big_orders").kind)
            assertEquals(DbObjectKind.ROUTINE, byName.getValue("add2").kind)
        }
    }

    @Test fun detailsHaveKeysIndexesAndAutoIncrement() {
        intro().use { i ->
            assertTrue(i.details(DbObject(app, "customers", DbObjectKind.TABLE)).columns.first().autoIncrement)
            val d = i.details(DbObject(app, "orders", DbObjectKind.TABLE))
            assertEquals(listOf("id", "customer_id", "total"), d.columns.map { it.name })
            assertEquals(listOf("id"), d.primaryKey)
            assertTrue(d.indexes.any { it.name == "orders_customer_idx" && it.columns == listOf("customer_id") })
            val fk = d.foreignKeys.single()
            assertEquals("fk_orders_customer", fk.name)
            assertEquals("customers", fk.refTable)
            assertEquals(listOf("id"), fk.refColumns)
        }
    }

    @Test fun underscoreInATableNameIsNotAWildcard() {
        intro().use { i ->
            assertEquals(listOf("id"), i.details(DbObject(app, "user_roles", DbObjectKind.TABLE)).columns.map { it.name })
        }
    }

    @Test fun ddlUsesShowCreate() {
        intro().use { i ->
            val table = i.ddl(DbObject(app, "orders", DbObjectKind.TABLE))
            assertContains(table, "CREATE TABLE `orders`")
            assertContains(table, "fk_orders_customer")
            assertTrue(table.endsWith(";"))
            assertContains(i.ddl(DbObject(app, "big_orders", DbObjectKind.VIEW)), "big_orders")
            assertContains(i.ddl(DbObject(app, "add2", DbObjectKind.ROUTINE)), "add2")
        }
    }

    @Test fun selectAllIsRunnableAndQuotedWithBackticks() {
        intro().use { i ->
            val sql = i.dialect.selectAll(DbObject(app, "customers", DbObjectKind.TABLE))
            assertEquals("select * from `app`.`customers`", sql)
            Session(fx.ds, fx.password).use { s -> assertIs<StatementResult.Rows>(s.runScript(listOf(sql)).single()) }
        }
    }
}

class MySqlIntrospectionTest : MySqlFamilyBase() {
    override val fx get() = Companion.fx
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            // The container's own user cannot create databases; root shares the container password.
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MariaDbIntrospectionTest : MySqlFamilyBase() {
    override val fx get() = Companion.fx
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
