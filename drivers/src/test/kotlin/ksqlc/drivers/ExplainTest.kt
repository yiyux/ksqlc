package ksqlc.drivers

import ksqlc.core.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class ExplainTest {
    @Test fun eachEngineGetsItsOwnForm() {
        assertEquals(listOf("explain select 1"), explainScript(DbKind.POSTGRES, "select 1", false)!!.statements)
        assertEquals(listOf("explain (analyze, buffers) select 1"), explainScript(DbKind.POSTGRES, "select 1", true)!!.statements)
        assertEquals(listOf("explain analyze select 1"), explainScript(DbKind.MYSQL, "select 1", true)!!.statements)
        assertEquals(listOf("analyze select 1"), explainScript(DbKind.MARIADB, "select 1", true)!!.statements)
        assertEquals(listOf("explain query plan select 1"), explainScript(DbKind.SQLITE, "select 1", false)!!.statements)
        val ms = explainScript(DbKind.MSSQL, "select 1", false)!!
        assertEquals(listOf("set showplan_text on", "select 1"), ms.statements); assertEquals(listOf("set showplan_text off"), ms.cleanup)
        assertEquals("explain plan for select 1", explainScript(DbKind.ORACLE, "select 1", false)!!.statements.first())
        assertEquals(listOf("explain select 1"), explainScript(DbKind.TRINO, "select 1", false)!!.statements)
        assertEquals(listOf("explain analyze select 1"), explainScript(DbKind.TRINO, "select 1", true)!!.statements)
        for (k in listOf(DbKind.SQLITE, DbKind.MSSQL, DbKind.ORACLE)) assertNull(explainScript(k, "select 1", true), k.name)
    }

    @Test fun sqliteShowsAPlanWithoutRunningTheStatement() {
        val file = Files.createTempFile("ksqlc-explain", ".db").also { it.toFile().deleteOnExit() }.toString()
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().execute("create table t(id integer primary key, v text)"); it.createStatement().execute("insert into t values (1, 'a')") }
        Session(DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite:$file"), null).use { s ->
            val r = s.runScript(explainScript(DbKind.SQLITE, "delete from t where id = 1", false)!!.statements).single() as StatementResult.Rows
            assertTrue(r.cursor.fetch(10).isNotEmpty())
            r.cursor.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> assertTrue(c.createStatement().executeQuery("select count(*) from t").let { it.next(); it.getInt(1) } == 1) }
    }
}
