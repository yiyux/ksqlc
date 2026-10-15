package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.nio.file.Files
import kotlin.test.*

class SchemaDiffTest {
    private fun db(vararg sql: String): String {
        val f = Files.createTempFile("ksqlc-diff", ".db").also { it.toFile().deleteOnExit() }.toString()
        Session(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$f"), null).use { s -> s.runScript(sql.toList()).forEach { assertFalse(it is StatementResult.Failed, it.toString()) } }
        return f
    }
    private fun intro(file: String) = Introspector(DataSource("x", "x", DbKind.SQLITE, "jdbc:sqlite:$file"), null)
    private val main = Namespace(null, "main")

    @Test fun classifiesObjectsAsOnlyLeftOnlyRightDifferentOrSame() {
        val a = db(
            "create table same_t(id integer primary key, v text)",
            "create table changed_t(id integer primary key, v text)",
            "create table only_a(id integer)",
            "create view vw as select id from same_t",
        )
        val b = db(
            "create table same_t(id integer primary key, v text)",
            "create table changed_t(id integer primary key, v text, extra integer)",
            "create table only_b(id integer)",
            "create view vw as select id, v from same_t",
        )
        intro(a).use { l -> intro(b).use { r ->
            val progress = ArrayList<Pair<Int, Int>>()
            val d = diffSchemas(l, main, r, main, { done, total -> progress += done to total })
            assertEquals(
                listOf("changed_t" to ObjectStatus.DIFFERENT, "only_a" to ObjectStatus.ONLY_LEFT, "only_b" to ObjectStatus.ONLY_RIGHT, "same_t" to ObjectStatus.SAME, "vw" to ObjectStatus.DIFFERENT),
                d.map { it.name to it.status },
            )
            assertNull(d.first { it.name == "only_a" }.right)
            assertTrue("extra" in d.first { it.name == "changed_t" }.right!!)
            assertEquals(5 to 5, progress.last())
            // stopping early returns what was compared so far
            var calls = 0
            assertEquals(2, diffSchemas(l, main, r, main, cancelled = { calls++ >= 2 }).size)
        } }
    }

    @Test fun normalizeDdlStripsTheSchemaPrefixInEveryQuotingStyle() {
        val ns = Namespace(null, "shop")
        assertEquals("create table t (id int)", normalizeDdl("create table shop.t (id int)  \r\n", ns))
        assertEquals("create table t (a int)\nref t", normalizeDdl("create table \"shop\".t (a int)\nref `shop`.t", ns))
        assertEquals("select * from t", normalizeDdl("select * from [shop].t", ns))
        // another schema, or a name that merely ends like it, is left alone
        assertEquals("from other.t, myshop.t", normalizeDdl("from other.t, myshop.t", ns))
    }
}
