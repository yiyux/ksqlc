package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

/** Schemas with different names but the same tables compare equal; real differences are found. */
class PostgresDiffTest {
    @Test fun sameContentInTwoSchemasIsEqualAndDifferencesAreFound() {
        Introspector(fx.ds, fx.password).use { i ->
            val d = diffSchemas(i, Namespace(null, "blue"), i, Namespace(null, "green")).associate { it.name to it.status }
            assertEquals(ObjectStatus.SAME, d["customers"])
            assertEquals(ObjectStatus.SAME, d["v_customers"])
            assertEquals(ObjectStatus.DIFFERENT, d["orders"])
            assertEquals(ObjectStatus.ONLY_LEFT, d["only_blue"])
            assertEquals(ObjectStatus.ONLY_RIGHT, d["only_green"])
        }
    }

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fun schema(s: String, extra: List<String>) = listOf(
                "create schema $s",
                "create table $s.customers(id serial primary key, email text not null unique)",
                "create view $s.v_customers as select id, email from $s.customers",
            ) + extra
            fx = fixture(
                DbKind.POSTGRES, c.jdbcUrl, c.username, c.password,
                schema("blue", listOf("create table blue.orders(id int primary key, total numeric(10,2))", "create table blue.only_blue(a int)")) +
                    schema("green", listOf("create table green.orders(id int primary key, total numeric(12,2), note text)", "create table green.only_green(a int)")),
            )
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
