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
import org.testcontainers.oracle.OracleContainer
import java.sql.DriverManager
import kotlin.test.*

/** Date and time columns of a real Oracle: what the driver reports, what the picker offers and writes, what is stored. */
@OptIn(ExperimentalTestApi::class)
class OracleTemporalTest {
    companion object {
        private var container: OracleContainer? = null
        private lateinit var ds: DataSource
        private lateinit var password: String
        private lateinit var owner: String

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = OracleContainer("gvenzl/oracle-free:23-slim-faststart").also { it.start() }
            container = c; owner = c.username.uppercase(); password = c.password
            ds = DataSource("or", "or", DbKind.ORACLE, c.jdbcUrl, c.username)
            DriverManager.getConnection(c.jdbcUrl, c.username, c.password).use {
                it.createStatement().execute("create table ev(id number primary key, d date, ts timestamp(6), tstz timestamp(6) with time zone, tsltz timestamp(6) with local time zone, note varchar2(100))")
            }
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private fun reset(vararg rows: String) = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        c.createStatement().apply { execute("delete from ev"); rows.forEach { execute("insert into ev $it") } }
    }

    private fun open(): DataTab {
        val obj = DbObject(Namespace(null, owner), "EV", DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.ORACLE)
        val session = Session(ds, password)
        val sql = d.selectAll(obj) + " order by 1"
        val r = session.runScript(listOf(sql)).single() as StatementResult.Rows
        return DataTab(obj, ResultTab("EV", r).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.ORACLE, listOf("ID")))
    }

    private fun stored(sql: String): List<List<Any?>> = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        val rs = c.createStatement().executeQuery(sql); val n = rs.metaData.columnCount
        buildList { while (rs.next()) add(List(n) { rs.getString(it + 1) }) }
    }

    private fun DataTab.use(block: (DataTab) -> Unit) = try { block(this) } finally { close() }
    private fun frames() = repeat(4) { rule.mainClock.advanceTimeByFrame() }

    private val row1 = "values (1, to_date('2026-01-31 10:20:30', 'YYYY-MM-DD HH24:MI:SS'), timestamp '2026-01-31 10:20:30', timestamp '2026-01-31 10:20:30 +01:00', timestamp '2026-01-31 10:20:30', 'x')"

    @Test fun theDriverReportsTheColumnTypesThePickerNeeds() {
        reset(row1)
        open().use { tab ->
            val types = tab.grid.result.columns.map { it.typeName }
            assertEquals(
                listOf(null, TemporalKind.DATETIME, TemporalKind.DATETIME, TemporalKind.DATETIME_TZ, TemporalKind.DATETIME, null),
                types.map { temporalKind(it, DbKind.ORACLE) }, types.toString(),
            )
        }
    }

    @Test fun whatTheGridShowsIsReadBackByThePicker() {
        reset(row1)
        open().use { tab ->
            val r = tab.grid.rows[0]
            assertEquals("2026-01-31 10:20:30", formatMoment(parseMoment(r[1].toString(), TemporalKind.DATETIME)!!, TemporalKind.DATETIME), r[1].toString())   // DATE keeps its time
            assertEquals("2026-01-31 10:20:30", formatMoment(parseMoment(r[2].toString(), TemporalKind.DATETIME)!!, TemporalKind.DATETIME), r[2].toString())
            val tz = parseMoment(r[3].toString(), TemporalKind.DATETIME_TZ)!!
            assertEquals(java.time.OffsetDateTime.parse("2026-01-31T10:20:30+01:00").toInstant(), tz.date.atTime(tz.time).atOffset(tz.offset).toInstant(), r[3].toString())
        }
    }

    @Test fun pickedValuesAreStoredAsTheSameInstantAndTime() {
        reset(row1)
        open().use { tab ->
            tab.editor.setCell(0, 1, CellValue.Text(formatMoment(parseMoment("2026-12-24 23:59:58", TemporalKind.DATETIME)!!, TemporalKind.DATETIME)), tab.grid)
            tab.editor.setCell(0, 3, CellValue.Text(formatMoment(parseMoment("2026-02-20 14:05:09+05:30", TemporalKind.DATETIME_TZ)!!, TemporalKind.DATETIME_TZ)), tab.grid)
            tab.editor.setCell(0, 4, CellValue.Text("2026-03-08 01:02:03"), tab.grid)
            assertNull(tab.submit())
            val r = stored("select to_char(d, 'YYYY-MM-DD HH24:MI:SS'), to_char(tstz, 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), to_char(tsltz, 'YYYY-MM-DD HH24:MI:SS') from ev").single()
            assertEquals(listOf<Any?>("2026-12-24 23:59:58", "2026-02-20 14:05:09 +05:30", "2026-03-08 01:02:03"), r)
        }
    }

    @Test fun anInsertedRowKeepsEveryTemporalKind() {
        reset()
        open().use { tab ->
            tab.editor.addRow(6)
            val row = tab.grid.rows.size
            tab.editor.setCell(row, 0, CellValue.Text("9"), tab.grid)
            tab.editor.setCell(row, 1, CellValue.Text("2026-03-08 23:30:00"), tab.grid)
            tab.editor.setCell(row, 2, CellValue.Text("2026-03-08 23:30:00"), tab.grid)
            tab.editor.setCell(row, 3, CellValue.Text("2026-03-08 23:30:00-03:00"), tab.grid)
            tab.editor.setCell(row, 5, CellValue.Text("ñandú"), tab.grid)
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("2026-03-08 23:30:00", "2026-03-08 23:30:00 -03:00", "ñandú"),
                stored("select to_char(d, 'YYYY-MM-DD HH24:MI:SS'), to_char(tstz, 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), note from ev").single().let { listOf(it[0], it[1], it[2]) }.let { it })
        }
    }

    @Test fun theCalendarOffersTheClockForAnOracleDateAndKeepsTheOffsetOfATimestampWithZone() {
        reset(row1)
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.DARK) { Grid(tab.grid, {}, Modifier.size(900.dp, 420.dp), editing = tab.editor) } }
            frames()
            rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
            rule.onNodeWithTag("h-value").assertExists()                       // a DATE has a time of day in Oracle
            rule.onNodeWithTag("day:2026-01-12").performClick(); frames()
            rule.onNodeWithTag("h+").performClick(); frames()
            rule.onNodeWithTag("date-ok").performClick(); frames()
            rule.onNodeWithTag("cell:0:3").performMouseInput { doubleClick() }; frames()
            rule.onNodeWithTag("offset").assertTextContains("UTC+01:00")
            rule.onNodeWithTag("day:2026-01-20").performClick(); frames()
            rule.onNodeWithTag("date-ok").performClick(); frames()
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("2026-01-12 11:20:30", "2026-01-20 10:20:30 +01:00"),
                stored("select to_char(d, 'YYYY-MM-DD HH24:MI:SS'), to_char(tstz, 'YYYY-MM-DD HH24:MI:SS TZH:TZM') from ev").single())
        }
    }
}
