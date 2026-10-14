package ksqlc.drivers

import ksqlc.core.DbKind
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeDumpTest {
    private val out = Path.of("/tmp/out.sql")
    private val pg = JdbcUrl.Parts(DbKind.POSTGRES, "db.local", 5433, "shop", false)
    private val my = JdbcUrl.Parts(DbKind.MYSQL, "db.local", null, "shop", false)

    @Test fun postgresDumpArgumentsAndPasswordInTheEnvironment() {
        val s = dumpCommand("/x/pg_dump", DbKind.POSTGRES, pg, "ana", "s3cret", DumpScope("App", "Items"), DumpOptions(schemaOnly = true, clean = true), out)
        assertEquals(
            listOf("/x/pg_dump", "-h", "db.local", "-p", "5433", "-U", "ana", "-w", "-d", "shop", "-n", "\"App\"", "-t", "\"App\".\"Items\"", "--schema-only", "--clean", "--if-exists", "-F", "p", "-f", "/tmp/out.sql"),
            s.args,
        )
        assertEquals(mapOf("PGPASSWORD" to "s3cret"), s.env)
        assertTrue(s.args.none { "s3cret" in it })
        assertEquals("c", dumpCommand("pg_dump", DbKind.POSTGRES, pg, null, null, DumpScope(), DumpOptions(custom = true), out).args.let { it[it.indexOf("-F") + 1] })
    }

    @Test fun mysqlDumpUsesTheDefaultPortTheScopeAndAnEnvPassword() {
        val s = dumpCommand("mysqldump", DbKind.MYSQL, my, "root", "pw", DumpScope(table = "t1"), DumpOptions(dataOnly = true), out)
        assertEquals(listOf("mysqldump", "-h", "db.local", "-P", "3306", "-u", "root", "--protocol=tcp", "--single-transaction", "--routines", "--triggers", "--no-create-info", "--skip-add-drop-table", "--result-file=/tmp/out.sql", "shop", "t1"), s.args)
        assertEquals(mapOf("MYSQL_PWD" to "pw"), s.env)
        assertEquals("other", dumpCommand("mysqldump", DbKind.MYSQL, my, null, null, DumpScope(schema = "other"), DumpOptions(), out).args.let { it[it.size - 1] })
    }

    @Test fun sqliteWritesToStdoutAndCannotDumpOnlyData() {
        val parts = JdbcUrl.Parts(DbKind.SQLITE, "", null, "/data/app.db", false)
        val s = dumpCommand("sqlite3", DbKind.SQLITE, parts, null, null, DumpScope(table = "t"), DumpOptions(), out)
        assertEquals(listOf("sqlite3", "/data/app.db", ".dump 't'"), s.args)
        assertEquals(out, s.stdout)
        assertFailsWith<IllegalArgumentException> { dumpCommand("sqlite3", DbKind.SQLITE, parts, null, null, DumpScope(), DumpOptions(dataOnly = true), out) }
        assertFailsWith<IllegalArgumentException> { dumpCommand("pg_dump", DbKind.POSTGRES, pg, null, null, DumpScope(), DumpOptions(schemaOnly = true, dataOnly = true), out) }
    }

    @Test fun restoreCommands() {
        val plain = restoreCommand("psql", DbKind.POSTGRES, pg, "ana", "pw", out)
        assertEquals(listOf("psql", "-h", "db.local", "-p", "5433", "-U", "ana", "-w", "-d", "shop", "-v", "ON_ERROR_STOP=1", "-f", "/tmp/out.sql"), plain.args)
        val custom = restoreCommand("pg_restore", DbKind.POSTGRES, pg, null, null, out, custom = true)
        assertEquals(listOf("pg_restore", "-h", "db.local", "-p", "5433", "-w", "-d", "shop", "--single-transaction", "/tmp/out.sql"), custom.args)
        val m = restoreCommand("mysql", DbKind.MYSQL, my, "root", "pw", out)
        assertEquals(out, m.stdin); assertEquals("shop", m.args.last()); assertEquals(mapOf("MYSQL_PWD" to "pw"), m.env)
    }

    @Test fun blockers() {
        assertNull(nativeDumpBlocker(DbKind.POSTGRES, pg))
        assertNotNull(nativeDumpBlocker(DbKind.MSSQL, pg))
        assertNotNull(nativeDumpBlocker(DbKind.ORACLE, null))
        assertNotNull(nativeDumpBlocker(DbKind.POSTGRES, null))
        assertNotNull(nativeDumpBlocker(DbKind.SQLITE, JdbcUrl.Parts(DbKind.SQLITE, "", null, "", false)))
    }

    @Test fun findsToolsInPathExtraDirsAndOverrides() {
        val a = Files.createTempDirectory("ksqlc-tools-a"); val b = Files.createTempDirectory("ksqlc-tools-b")
        fun tool(dir: Path, name: String) = Files.createFile(dir.resolve(name)).also { it.toFile().setExecutable(true) }
        val inB = tool(b, "mariadb-dump"); val inA = tool(a, "mysqldump")
        Files.createFile(a.resolve("pg_dump"))   // present but not executable
        assertEquals(inB, findTool(listOf("mariadb-dump", "mysqldump"), path = a.toString(), extraDirs = listOf(b.toString())))
        assertEquals(inA, findTool(listOf("nope", "mysqldump"), path = a.toString(), extraDirs = emptyList()))
        assertNull(findTool(listOf("pg_dump"), path = a.toString(), extraDirs = emptyList()))
        assertEquals(inB, findTool(listOf("mysqldump"), override = inB.toString(), path = a.toString(), extraDirs = emptyList()))
        assertEquals(inA, findTool(listOf("mysqldump"), override = "/no/such", path = a.toString(), extraDirs = emptyList()))
    }

    @Test fun sqliteDumpAndRestoreWithTheRealTool() {
        val sqlite = findTool(listOf("sqlite3"))
        assumeTrue("sqlite3 no está instalado", sqlite != null)
        val db1 = Files.createTempFile("ksqlc-dump", ".db").also { Files.delete(it) }
        val db2 = Files.createTempFile("ksqlc-dump", ".db").also { Files.delete(it) }
        val file = Files.createTempFile("ksqlc-dump", ".sql")
        val lines = ArrayList<String>()
        fun run(spec: ProcessSpec) = runProcess(spec, lines::add)
        assertEquals(0, run(ProcessSpec(listOf(sqlite.toString(), db1.toString(), "create table t(a integer primary key, b text); insert into t values (1, 'x, \"y\"'), (2, null);"))))
        val parts1 = JdbcUrl.Parts(DbKind.SQLITE, "", null, db1.toString(), false)
        assertEquals(0, run(dumpCommand(sqlite.toString(), DbKind.SQLITE, parts1, null, null, DumpScope(), DumpOptions(), file)))
        assertTrue("CREATE TABLE t" in Files.readString(file))
        val parts2 = JdbcUrl.Parts(DbKind.SQLITE, "", null, db2.toString(), false)
        assertEquals(0, run(restoreCommand(sqlite.toString(), DbKind.SQLITE, parts2, null, null, file)))
        val out = ArrayList<String>()
        runProcess(ProcessSpec(listOf(sqlite.toString(), db2.toString(), "select a || '|' || coalesce(b, '-') from t order by a")), out::add)
        assertEquals(listOf("1|x, \"y\"", "2|-"), out)
    }
}
