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
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.time.OffsetDateTime
import kotlin.test.*

/** Date and time columns of a real PostgreSQL: what the driver reports, what the picker writes, and what ends up stored. */
@OptIn(ExperimentalTestApi::class)
class PostgresTemporalTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var ds: DataSource
        private lateinit var password: String

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            ds = DataSource("pg", "pg", DbKind.POSTGRES, c.jdbcUrl, c.username); password = c.password
            DriverManager.getConnection(c.jdbcUrl, c.username, c.password).use {
                it.createStatement().execute("create table ev(id int primary key, tz timestamptz, ts timestamp, d date, tm time, note text)")
            }
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private fun reset(vararg rows: String) = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        c.createStatement().apply { execute("truncate ev"); rows.forEach { execute("insert into ev values $it") } }
    }

    private fun open(): DataTab {
        val obj = DbObject(Namespace(null, "public"), "ev", DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.POSTGRES)
        val session = Session(ds, password)
        val sql = d.selectAll(obj) + " order by id"
        val r = session.runScript(listOf(sql)).single() as StatementResult.Rows
        return DataTab(obj, ResultTab("ev", r).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.POSTGRES, listOf("id")))
    }

    private fun stored(sql: String): List<List<Any?>> = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        val rs = c.createStatement().executeQuery(sql); val n = rs.metaData.columnCount
        buildList { while (rs.next()) add(List(n) { rs.getObject(it + 1) }) }
    }

    private fun instant(sql: String) = stored(sql).single().single().let { (it as java.sql.Timestamp).toInstant() }

    @Test fun theDriverReportsTheColumnTypesThePickerNeeds() {
        reset("(1, '2026-01-31 10:20:30+01:00', '2026-01-31 10:20:30', '2026-01-31', '10:20:30', 'x')")
        open().let { tab ->
            tab.use {
                val kinds = tab.grid.result.columns.map { temporalKind(it.typeName) }
                assertEquals(listOf(null, TemporalKind.DATETIME_TZ, TemporalKind.DATETIME, TemporalKind.DATE, TemporalKind.TIME, null), kinds, tab.grid.result.columns.toString())
            }
        }
    }

    @Test fun whatTheGridShowsForATimestamptzIsReadBackByThePicker() {
        reset("(1, '2026-07-15 10:20:30+02:00', null, null, null, null)")      // a summer date: the machine's offset then may differ from today's
        open().use { tab ->
            val shown = tab.grid.rows[0][1].toString()
            val m = parseMoment(shown, TemporalKind.DATETIME_TZ)
            assertNotNull(m, shown)
            // the instant the picker understands is the instant stored in the database
            assertEquals(OffsetDateTime.parse("2026-07-15T10:20:30+02:00").toInstant(), m.date.atTime(m.time).atOffset(m.offset).toInstant())
        }
    }

    @Test fun aPickedTimestamptzIsStoredAsTheSameInstant() {
        reset("(1, '2026-01-31 10:20:30+01:00', null, null, null, null)")
        open().use { tab ->
            // what the picker writes for "20 February, 14:05:09 at +05:30"
            val text = formatMoment(parseMoment("2026-02-20 14:05:09+05:30", TemporalKind.DATETIME_TZ)!!, TemporalKind.DATETIME_TZ)
            assertEquals("2026-02-20 14:05:09+05:30", text)
            tab.editor.setCell(0, 1, CellValue.Text(text), tab.grid)
            assertNull(tab.submit())
            assertEquals(OffsetDateTime.parse("2026-02-20T14:05:09+05:30").toInstant(), instant("select tz from ev where id = 1"))
            assertEquals("2026-02-20 08:35:09", stored("select to_char(tz at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') from ev").single().single())
        }
    }

    @Test fun everyTemporalKindRoundTripsThroughAnInsertedRow() {
        reset()
        open().use { tab ->
            tab.editor.addRow(6)
            val row = tab.grid.rows.size
            fun put(col: Int, kind: TemporalKind, typed: String) = tab.editor.setCell(row, col, CellValue.Text(formatMoment(parseMoment(typed, kind)!!, kind)), tab.grid)
            tab.editor.setCell(row, 0, CellValue.Text("7"), tab.grid)
            put(1, TemporalKind.DATETIME_TZ, "2026-03-08 23:30:00-03:00")
            put(2, TemporalKind.DATETIME, "2026-03-08 23:30:00")
            put(3, TemporalKind.DATE, "2026-03-08")
            put(4, TemporalKind.TIME, "23:30:00")
            assertNull(tab.submit())
            val r = stored("select ts::text, d::text, tm::text, (tz at time zone 'UTC')::text from ev").single()
            assertEquals(listOf<Any?>("2026-03-08 23:30:00", "2026-03-08", "23:30:00", "2026-03-09 02:30:00"), r)
        }
    }

    @Test fun theCalendarEditsATimestamptzCellEndToEnd() {
        reset("(1, '2026-01-31 10:20:30+00:00', null, null, null, null)")
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.DARK) { Grid(tab.grid, {}, Modifier.size(700.dp, 420.dp), editing = tab.editor) } }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }
            repeat(4) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("date-picker").assertExists()
            val zone = parseMoment(tab.grid.rows[0][1].toString(), TemporalKind.DATETIME_TZ)!!.offset
            rule.onNodeWithTag("offset").assertTextContains("UTC" + zone.id.let { if (it == "Z") "+00:00" else it })
            // the grid shows the instant in this machine's zone; pick the 12th of that month and keep the clock
            val shownDay = parseMoment(tab.grid.rows[0][1].toString(), TemporalKind.DATETIME_TZ)!!.date
            val target = shownDay.withDayOfMonth(12)
            rule.onNodeWithTag("day:$target").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("date-ok").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            val text = (tab.editor.valueAt(0, 1, tab.grid) as CellValue.Text).text
            assertTrue(text.startsWith("$target "), text)
            assertNull(tab.submit())
            val expected = parseMoment(text, TemporalKind.DATETIME_TZ)!!.let { it.date.atTime(it.time).atOffset(it.offset).toInstant() }
            assertEquals(expected, instant("select tz from ev where id = 1"))
        }
    }

    private fun DataTab.use(block: (DataTab) -> Unit) = try { block(this) } finally { close() }
}
