package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Session
import ksqlc.drivers.Introspector
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

class PostgresIntrospectionTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema app",
                "create table app.customers(id serial primary key, email varchar(120) not null unique, note text)",
                "create table app.orders(id int primary key, customer_id int not null references app.customers(id), total numeric(10,2) default 0)",
                "create index orders_customer_idx on app.orders(customer_id)",
                "create view app.big_orders as select * from app.orders where total > 100",
                "create materialized view app.mv as select 1 as x",
                "create function app.add(a int, b int) returns int language sql as 'select a+b'",
                "create table app.user_roles(id int)",
                "create table app.userXroles(id int, extra int)",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val app = Namespace(null, "app")
    @Test fun systemSchemasAppearOnlyWhenAsked() = intro().use { i ->
        assertTrue(i.namespaces().none { it.label == "pg_catalog" || it.label == "information_schema" })
        i.includeSystem = true
        val names = i.namespaces().map { it.label }
        assertTrue("pg_catalog" in names && "information_schema" in names, names.toString())
        assertTrue(names.none { it.startsWith("pg_toast") || it.startsWith("pg_temp") }, names.toString())
        val ns = i.namespaces().first { it.label == "pg_catalog" }
        assertTrue(i.objects(ns).any { it.name == "pg_class" })
        assertTrue(i.details(i.objects(ns).first { it.name == "pg_class" }).columns.any { it.name == "relname" })
    }


    @Test fun theTimeZoneAndStartupScriptApplyAndReadOnlyHoldsEvenForWritingCtes() {
        Session(fx.ds.copy(options = ConnectionOptions(timeZone = "Asia/Tokyo", startupScript = "set application_name = 'ksqlc-opts'")), fx.password).use { s ->
            fun one(sql: String) = (s.runScript(listOf(sql)).single() as StatementResult.Rows).let { r -> r.cursor.fetch(1).single().single().toString().also { r.cursor.close() } }
            assertEquals("Asia/Tokyo", one("show timezone")); assertEquals("ksqlc-opts", one("show application_name"))
        }
        Session(fx.ds.copy(options = ConnectionOptions(readOnly = true)), fx.password).use { s ->
            assertTrue(s.runScript(listOf("insert into app.customers(email) values ('x@x')")).single() is StatementResult.Failed)      // refused before it is sent
            // a WITH that writes is stopped by the text check
            val sneaky = s.runScript(listOf("with ins as (insert into app.customers(email) values ('y@y') returning 1) select * from ins")).single()
            assertTrue(sneaky is StatementResult.Failed && "solo lectura" in sneaky.message, sneaky.toString())
            // a plain SELECT that writes through a function gets past the check, so the session itself refuses
            val setval = s.runScript(listOf("select setval('app.customers_id_seq', 999)")).single()
            assertTrue(setval is StatementResult.Failed && "read-only" in setval.message.lowercase(), setval.toString())
        }
    }

    private fun intro() = Introspector(fx.ds, fx.password)

    @Test fun namespacesHideSystemSchemas() {
        intro().use { i ->
            val names = i.namespaces().map { it.label }
            assertTrue("app" in names && "public" in names, names.toString())
            assertTrue(names.none { it.startsWith("pg_") || it == "information_schema" }, names.toString())
        }
    }

    @Test fun objectsHaveKindsAndRoutines() {
        intro().use { i ->
            val byName = i.objects(app).associateBy { it.name }
            assertEquals(DbObjectKind.TABLE, byName.getValue("customers").kind)
            assertEquals(DbObjectKind.VIEW, byName.getValue("big_orders").kind)
            assertEquals(DbObjectKind.MATERIALIZED_VIEW, byName.getValue("mv").kind)
            assertEquals(DbObjectKind.SEQUENCE, byName.getValue("customers_id_seq").kind)
            assertEquals(DbObjectKind.ROUTINE, byName.getValue("add(a integer, b integer)").kind)
        }
    }

    @Test fun detailsHaveKeysIndexesAndDefaults() {
        intro().use { i ->
            val d = i.details(DbObject(app, "orders", DbObjectKind.TABLE))
            assertEquals(listOf("id", "customer_id", "total"), d.columns.map { it.name })
            assertEquals(listOf("id"), d.primaryKey)
            assertFalse(d.columns[1].nullable)
            assertTrue(d.indexes.any { it.name == "orders_customer_idx" && it.columns == listOf("customer_id") })
            val fk = d.foreignKeys.single()
            assertEquals("customers", fk.refTable)
            assertEquals(listOf("customer_id"), fk.columns)
            assertEquals(listOf("id"), fk.refColumns)
        }
    }

    @Test fun underscoreInATableNameIsNotAWildcard() {
        intro().use { i ->
            val d = i.details(DbObject(app, "user_roles", DbObjectKind.TABLE))
            assertEquals(listOf("id"), d.columns.map { it.name })
        }
    }

    @Test fun ddlForEachKind() {
        intro().use { i ->
            val table = i.ddl(DbObject(app, "orders", DbObjectKind.TABLE))
            assertContains(table, "create table \"app\".\"orders\"")
            assertContains(table, "numeric(10,2)")
            assertContains(table, "foreign key (\"customer_id\") references \"app\".\"customers\" (\"id\")")
            assertContains(table, "create index \"orders_customer_idx\"")
            assertContains(i.ddl(DbObject(app, "big_orders", DbObjectKind.VIEW)), "create or replace view \"app\".\"big_orders\" as")
            assertContains(i.ddl(DbObject(app, "mv", DbObjectKind.MATERIALIZED_VIEW)), "create materialized view")
            assertContains(i.ddl(DbObject(app, "add(a integer, b integer)", DbObjectKind.ROUTINE)), "CREATE OR REPLACE FUNCTION app.add")
        }
    }

    @Test fun selectAllIsRunnable() {
        intro().use { i ->
            val sql = i.dialect.selectAll(DbObject(app, "customers", DbObjectKind.TABLE))
            ksqlc.drivers.Session(fx.ds, fx.password).use { s ->
                assertIs<StatementResult.Rows>(s.runScript(listOf(sql)).single())
            }
        }
    }
}
