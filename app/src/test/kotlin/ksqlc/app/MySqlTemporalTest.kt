package ksqlc.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ksqlc.core.*
import ksqlc.drivers.CellValue
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import ksqlc.storage.ThemeMode
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.testcontainers.DockerClientFactory
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager
import kotlin.test.*

private const val DDL = "create table ev(id int primary key, ts timestamp null default null, dt datetime, d date, tm time, note text)"

/** Date and time columns of a real MySQL-family server: reported types, what the picker writes, what is stored. */
@OptIn(ExperimentalTestApi::class)
abstract class MySqlTemporalBase(private val kind: DbKind) {
    protected abstract val ds: DataSource
    protected abstract val password: String

    @get:Rule val rule = createComposeRule()

    private fun reset(vararg rows: String) = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        c.createStatement().apply { execute("delete from ev"); rows.forEach { execute("insert into ev values $it") } }
    }

    private fun open(): DataTab {
        val obj = DbObject(Namespace(ds.url.substringAfterLast('/').substringBefore('?'), null), "ev", DbObjectKind.TABLE)
        val d = Dialect.of(kind)
        val session = Session(ds, password)
        val sql = d.selectAll(obj) + " order by id"
        val r = session.runScript(listOf(sql)).single() as StatementResult.Rows
        return DataTab(obj, ResultTab("ev", r).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, kind, listOf("id")))
    }

    private fun stored(sql: String): List<List<Any?>> = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        val rs = c.createStatement().executeQuery(sql); val n = rs.metaData.columnCount
        buildList { while (rs.next()) add(List(n) { rs.getString(it + 1) }) }
    }

    private fun DataTab.use(block: (DataTab) -> Unit) = try { block(this) } finally { close() }

    @Test fun theDriverReportsTheColumnTypesThePickerNeeds() {
        reset("(1, '2026-01-31 10:20:30', '2026-01-31 10:20:30', '2026-01-31', '10:20:30', 'x')")
        open().use { tab ->
            val types = tab.grid.result.columns.map { it.typeName }
            assertEquals(listOf(null, TemporalKind.DATETIME, TemporalKind.DATETIME, TemporalKind.DATE, TemporalKind.TIME, null), types.map { temporalKind(it) }, types.toString())
        }
    }

    @Test fun whatTheGridShowsIsReadBackByThePicker() {
        reset("(1, '2026-07-15 10:20:30', '2026-07-15 10:20:30', '2026-07-15', '10:20:30', null)")
        open().use { tab ->
            val row = tab.grid.rows[0]
            assertEquals(parseMoment("2026-07-15 10:20:30", TemporalKind.DATETIME)!!.copy(offset = java.time.ZoneOffset.UTC).let { it.date to it.time }, parseMoment(row[2].toString(), TemporalKind.DATETIME)!!.let { it.date to it.time }, row[2].toString())
            assertEquals("2026-07-15", formatMoment(parseMoment(row[3].toString(), TemporalKind.DATE)!!, TemporalKind.DATE))
            assertEquals("10:20:30", formatMoment(parseMoment(row[4].toString(), TemporalKind.TIME)!!, TemporalKind.TIME))
        }
    }

    @Test fun pickedValuesAreStoredAsTyped() {
        reset("(1, '2026-01-31 10:20:30', '2026-01-31 10:20:30', '2026-01-31', '10:20:30', 'x')")
        open().use { tab ->
            tab.editor.setCell(0, 2, CellValue.Text(formatMoment(parseMoment("2026-12-24 23:59:58", TemporalKind.DATETIME)!!, TemporalKind.DATETIME)), tab.grid)
            tab.editor.setCell(0, 3, CellValue.Text("2026-02-28"), tab.grid)
            tab.editor.setCell(0, 4, CellValue.Text(formatMoment(parseMoment("07:05:03", TemporalKind.TIME)!!, TemporalKind.TIME)), tab.grid)
            tab.editor.setCell(0, 1, CellValue.Null, tab.grid)
            assertNull(tab.submit())
            assertEquals(listOf<Any?>(null, "2026-12-24 23:59:58", "2026-02-28", "07:05:03"), stored("select ts, dt, d, tm from ev where id = 1").single())
        }
    }

    @Test fun anInsertedRowKeepsEveryTemporalKind() {
        reset()
        open().use { tab ->
            tab.editor.addRow(6)
            val row = tab.grid.rows.size
            tab.editor.setCell(row, 0, CellValue.Text("9"), tab.grid)
            tab.editor.setCell(row, 1, CellValue.Text("2026-03-08 01:02:03"), tab.grid)
            tab.editor.setCell(row, 2, CellValue.Text("2026-03-08 23:30:00"), tab.grid)
            tab.editor.setCell(row, 3, CellValue.Text("2026-03-08"), tab.grid)
            tab.editor.setCell(row, 4, CellValue.Text("23:30:00"), tab.grid)
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("2026-03-08 01:02:03", "2026-03-08 23:30:00", "2026-03-08", "23:30:00"), stored("select ts, dt, d, tm from ev").single())
        }
    }

    @Test fun theCalendarEditsADatetimeCellEndToEnd() {
        reset("(1, null, '2026-01-31 10:20:30', null, null, null)")
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.LIGHT) { Grid(tab.grid, {}, Modifier.size(700.dp, 420.dp), editing = tab.editor) } }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("cell:0:2").performMouseInput { doubleClick() }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("date-picker").assertExists()
            rule.onNodeWithTag("offset").assertDoesNotExist()                      // no zone for a datetime
            rule.onNodeWithTag("day:2026-01-12").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("h+").performClick(); repeat(2) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("date-ok").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            assertNull(tab.submit())
            assertEquals("2026-01-12 11:20:30", stored("select dt from ev where id = 1").single().single())
        }
    }

    @Test fun aDateColumnClosesWithOneClickAndIsStored() {
        reset("(1, null, null, '2026-01-31', null, null)")
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.DARK) { Grid(tab.grid, {}, Modifier.size(700.dp, 420.dp), editing = tab.editor) } }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("cell:0:3").performMouseInput { doubleClick() }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("day:2026-01-20").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            assertNull(tab.submit())
            assertEquals("2026-01-20", stored("select d from ev where id = 1").single().single())
        }
    }
}

class MySqlTemporalTest : MySqlTemporalBase(DbKind.MYSQL) {
    override val ds get() = Companion.ds
    override val password get() = Companion.password
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var ds: DataSource
        private lateinit var password: String
        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c; ds = DataSource("my", "my", DbKind.MYSQL, c.jdbcUrl, c.username); password = c.password
            DriverManager.getConnection(c.jdbcUrl, c.username, c.password).use { it.createStatement().execute(DDL) }
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class MariaDbTemporalTest : MySqlTemporalBase(DbKind.MARIADB) {
    override val ds get() = Companion.ds
    override val password get() = Companion.password
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var ds: DataSource
        private lateinit var password: String
        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c; ds = DataSource("ma", "ma", DbKind.MARIADB, c.jdbcUrl, c.username); password = c.password
            DriverManager.getConnection(c.jdbcUrl, c.username, c.password).use { it.createStatement().execute(DDL) }
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
