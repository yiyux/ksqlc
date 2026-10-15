package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.Session
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

/** Runs [statements] on one session and returns the rows of the last one. */
internal fun lastRows(fx: ServerFixture, statements: List<String>): List<List<Any?>> = Session(fx.ds, fx.password).use { s ->
    when (val r = s.runScript(statements).last()) {
        is StatementResult.Rows -> r.cursor.fetch(10)
        is StatementResult.Failed -> fail("${r.sql}: ${r.message}")
        else -> fail("sin filas: ${r.sql}")
    }
}

class PostgresRoutineCallTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema rt",
                "create function rt.add(a int, b int) returns int language sql as 'select a + b'",
                "create function rt.add(a text, b text) returns text language sql as 'select a || b'",
                "create function rt.nameless(int, int) returns int language sql as 'select \$1 + \$2'",
                "create procedure rt.bump(in x int, out y int, inout z int) language plpgsql as \$\$ begin y := x; z := z + 1; end \$\$",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun routine(name: String) = DbObject(Namespace(null, "rt"), name, DbObjectKind.ROUTINE)

    @Test fun functionsBecomeASelectAndOverloadsAreToldApart() = Introspector(fx.ds, fx.password).use { i ->
        val names = i.objects(Namespace(null, "rt")).map { it.name }
        assertTrue("add(a integer, b integer)" in names && "add(a text, b text)" in names, "$names")
        assertEquals(listOf("select * from \"rt\".\"add\"(:a, :b)"), i.call(routine("add(a integer, b integer)")))
        assertEquals(listOf("select * from \"rt\".\"nameless\"(:arg1, :arg2)"), i.call(routine("nameless(integer, integer)")))
        assertEquals(listOf(5), lastRows(fx, listOf("select * from \"rt\".\"add\"(2, 3)")).single())
        assertEquals(listOf("ab"), lastRows(fx, listOf("select * from \"rt\".\"add\"('a', 'b')")).single())
    }

    @Test fun aProcedureGetsANullForItsOutArgument() = Introspector(fx.ds, fx.password).use { i ->
        val sql = i.call(routine(i.objects(Namespace(null, "rt")).first { it.name.startsWith("bump(") }.name)).single()      // the identity arguments leave the OUT one out
        assertEquals("call \"rt\".\"bump\"(:x, null, :z)", sql)
        assertEquals(listOf(1, 6), lastRows(fx, listOf(sql.replace(":x", "1").replace(":z", "5"))).single())
    }
}

class MySqlRoutineCallTest {
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, listOf(
                "create database rt",
                "create function rt.f(a int, b int) returns int deterministic return a + b",
                "create procedure rt.p(in x int, out y int, inout z int) begin set y = x; set z = z + 1; end",
                "create procedure rt.plain(in n int) select n * 2 as doubled",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun routine(name: String) = DbObject(Namespace("rt", null), name, DbObjectKind.ROUTINE)

    @Test fun aFunctionIsASelectAndAProcedureWithOutArgumentsShowsThemAfterwards() = Introspector(fx.ds, fx.password).use { i ->
        assertEquals(listOf("select `rt`.`f`(:a, :b) as `f`"), i.call(routine("f")))
        assertEquals(listOf("call `rt`.`plain`(:n)"), i.call(routine("plain")))
        val p = i.call(routine("p"))
        assertEquals(listOf("set @ksqlc_z = :z", "call `rt`.`p`(:x, @ksqlc_y, @ksqlc_z)", "select @ksqlc_y as y, @ksqlc_z as z"), p)
        assertEquals(listOf(1, 6), lastRows(fx, p.map { it.replace(":x", "1").replace(":z", "5") }).single().map { (it as Number).toInt() })
        assertEquals(5, (lastRows(fx, listOf("select `rt`.`f`(2, 3) as `f`")).single().single() as Number).toInt())
    }
}
