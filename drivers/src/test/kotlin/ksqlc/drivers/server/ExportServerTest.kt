package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.trino.TrinoContainer
import kotlin.test.*

/** Exporting a query by blocks against real servers: every row arrives once, and a cancelled export stops early. */
abstract class ExportServerBase {
    protected abstract val fx: ServerFixture
    /** A query returning the integers 1..[n] in a column called `x`, in any order. */
    protected abstract fun series(n: Int): String
    protected open val total = 10_000
    protected open fun query(n: Int) = series(n)

    @Test fun everyRowIsExportedExactlyOnce() = Session(fx.ds, fx.password).use { s ->
        val sb = StringBuilder()
        val written = exportQuery(s, query(total), { RowWriter(ExportFormat.CSV, it, sb, withHeader = false) })
        assertEquals(total.toLong(), written)
        assertEquals((1..total).toList(), sb.lines().filter { it.isNotEmpty() }.map { it.toInt() }.sorted())
    }

    @Test fun cancellingStopsBeforeTheEnd() = Session(fx.ds, fx.password).use { s ->
        var seen = 0L
        val written = exportQuery(s, query(total), { RowWriter(ExportFormat.CSV, it, StringBuilder()) }, cancelled = { seen >= 1000 }, onProgress = { seen = it })
        assertTrue(written in 1000 until total, "escribió $written de $total")
    }
}

/** Integers 1..n from the four decimal digits crossed, for the engines without a row generator. */
private fun digits(n: Int) = "(select 0 as d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5 union all select 6 union all select 7 union all select 8 union all select 9)"
    .let { d -> "select x from (select a.d + b.d * 10 + c.d * 100 + e.d * 1000 + 1 as x from $d a, $d b, $d c, $d e) g where x <= $n" }

class PostgresExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = "select x from generate_series(1, $n) as g(x)"

    /** The server must produce rows as they are asked for: the clock read for a later block is later than the first one. */
    @Test fun rowsAreProducedWhileTheyAreRead() = Session(fx.ds, fx.password).use { s ->
        val r = s.stream("select i, (extract(epoch from clock_timestamp()) * 1000)::bigint as ms from generate_series(1, 300) as g(i)", 100) as StatementResult.Rows
        val first = r.cursor.fetch(100)
        Thread.sleep(600)
        val rest = r.cursor.fetch(100)
        r.cursor.close()
        assertEquals(100, rest.size)
        val gap = (rest.last()[1] as Number).toLong() - (first.first()[1] as Number).toLong()
        assertTrue(gap >= 500, "las filas se calcularon de golpe (diferencia $gap ms): no hay cursor en el servidor")
    }

    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MySqlExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = digits(n)
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MariaDbExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = digits(n)
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class SqlServerExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = digits(n)
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            fx = fixture(DbKind.MSSQL, JdbcUrl.build(DbKind.MSSQL, c.host, c.getMappedPort(1433), "", false), c.username, c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class OracleExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = "select level as x from dual connect by level <= $n"
    companion object {
        private var container: OracleContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = OracleContainer("gvenzl/oracle-free:23-slim-faststart").also { it.start() }
            container = c
            fx = fixture(DbKind.ORACLE, c.jdbcUrl, c.username, c.password, emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class TrinoExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override fun series(n: Int) = "select x from unnest(sequence(1, $n)) as t(x)"
    companion object {
        private var container: TrinoContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = TrinoContainer("trinodb/trino:484").also { it.start() }
            container = c
            fx = fixture(DbKind.TRINO, "jdbc:trino://${c.host}:${c.firstMappedPort}", "test", "", emptyList())
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

/** Cassandra has no generator and no subqueries: the numbers are rows of a table filled at the start. */
class CassandraExportTest : ExportServerBase() {
    override val fx get() = Companion.fx
    override val total = 1500
    override fun series(n: Int) = error("no se usa: Cassandra lee de una tabla")
    override fun query(n: Int) = "select id as x from ksqlc_ex.series"
    companion object {
        private var container: CassandraContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = CassandraContainer("cassandra:5.0").also { it.start() }
            container = c
            val url = "jdbc:cassandra://${c.contactPoint.hostString}:${c.contactPoint.port}?localdatacenter=${c.localDatacenter}"
            fx = fixture(DbKind.CASSANDRA, url, "cassandra", "cassandra",
                listOf("create keyspace ksqlc_ex with replication = {'class': 'SimpleStrategy', 'replication_factor': 1}", "create table ksqlc_ex.series(id int primary key)") +
                    (1..1500).map { "insert into ksqlc_ex.series(id) values ($it)" })
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
