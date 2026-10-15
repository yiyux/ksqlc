package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.*

/** pg_dump / psql / pg_restore from this machine against a real PostgreSQL 18 (same major as the local pg_dump, since a newer dump may not restore on an older server) (skipped when the tools are not installed). */
class PostgresDumpTest {
    private fun parts(db: String) = JdbcUrl.Parts(DbKind.POSTGRES, c.host, c.getMappedPort(5432), db, false)
    private fun run(spec: ProcessSpec): Pair<Int, List<String>> { val l = ArrayList<String>(); return runProcess(spec, l::add) to l }

    private fun countIn(db: String): Long = Session(DataSource("x", "x", DbKind.POSTGRES, JdbcUrl.build(DbKind.POSTGRES, c.host, c.getMappedPort(5432), db, false), c.username), c.password).use { s ->
        val r = s.runScript(listOf("select count(*) from app.items where note = 'it''s, \"quoted\"' or note is null")).single() as StatementResult.Rows
        (r.cursor.fetch(1).single().single() as Number).toLong()
    }

    private fun roundTrip(custom: Boolean, newDb: String) {
        val dump = findTool(dumpToolNames(DbKind.POSTGRES)); val restore = findTool(restoreToolNames(DbKind.POSTGRES, custom))
        assumeTrue("pg_dump/psql/pg_restore no están instalados", dump != null && restore != null)
        Session(DataSource("x", "x", DbKind.POSTGRES, c.jdbcUrl, c.username), c.password).use { it.runScript(listOf("create database $newDb")) }
        val file = Files.createTempFile("ksqlc-pgdump", if (custom) ".dump" else ".sql")
        val (code, log) = run(dumpCommand(dump.toString(), DbKind.POSTGRES, parts(c.databaseName), c.username, c.password, DumpScope(schema = "app"), DumpOptions(custom = custom), file))
        assertEquals(0, code, log.toString())
        assertTrue(Files.size(file) > 0)
        val (rc, rlog) = run(restoreCommand(restore.toString(), DbKind.POSTGRES, parts(newDb), c.username, c.password, file, custom))
        assertEquals(0, rc, rlog.toString())
        assertEquals(2, countIn(newDb))
    }

    @Test fun plainDumpRestoresWithPsql() = roundTrip(custom = false, newDb = "restored_plain")
    @Test fun customDumpRestoresWithPgRestore() = roundTrip(custom = true, newDb = "restored_custom")

    @Test fun aFailingRestoreReportsANonZeroExit() {
        val psql = findTool(listOf("psql")); assumeTrue("psql no está instalado", psql != null)
        val bad = Files.createTempFile("ksqlc-bad", ".sql").also { Files.writeString(it, "select nada from no_existe;") }
        val (code, log) = run(restoreCommand(psql.toString(), DbKind.POSTGRES, parts(c.databaseName), c.username, c.password, bad))
        assertNotEquals(0, code)
        assertTrue(log.any { "no_existe" in it }, log.toString())
    }

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var c: PostgreSQLContainer
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            c = PostgreSQLContainer("postgres:18-alpine").also { it.start() }
            container = c
            fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema app",
                "create table app.items(id int primary key, note text)",
                "insert into app.items values (1, 'it''s, \"quoted\"'), (2, null)",
            ))
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
