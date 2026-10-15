package ksqlc.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ksqlc.core.*
import ksqlc.drivers.CellValue
import ksqlc.drivers.Introspector
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import ksqlc.storage.ThemeMode
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.testcontainers.DockerClientFactory
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager
import kotlin.test.*

/** Date and time columns of a real SQL Server (generic dialect): reported types, what the picker writes, what is stored. */
@OptIn(ExperimentalTestApi::class)
class SqlServerTemporalTest {
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var ds: DataSource
        private lateinit var password: String

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c
            val url = c.jdbcUrl.let { if ("encrypt" in it) it else "$it;encrypt=false" }
            ds = DataSource("ms", "ms", DbKind.MSSQL, url, c.username); password = c.password
            DriverManager.getConnection(url, c.username, c.password).use {
                it.createStatement().execute(
                    "create table ev(id int primary key, dt datetime2, dtz datetimeoffset, sdt smalldatetime, d date, tm time, flag bit, note nvarchar(100))",
                )
                it.createStatement().execute("create table auto(id int identity primary key, n int default 7)")
            }
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private fun reset(vararg rows: String) = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        c.createStatement().apply { execute("delete from ev"); rows.forEach { execute("insert into ev values $it") } }
    }

    private fun open(table: String = "ev", key: List<String> = listOf("id")): DataTab {
        val obj = DbObject(Namespace(null, "dbo"), table, DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.MSSQL)
        val session = Session(ds, password)
        val sql = d.selectAll(obj) + " order by 1"
        val r = session.runScript(listOf(sql)).single() as StatementResult.Rows
        return DataTab(obj, ResultTab(table, r).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.MSSQL, key))
    }

    private fun stored(sql: String): List<List<Any?>> = DriverManager.getConnection(ds.url, ds.user, password).use { c ->
        val rs = c.createStatement().executeQuery(sql); val n = rs.metaData.columnCount
        buildList { while (rs.next()) add(List(n) { rs.getString(it + 1) }) }
    }

    private fun DataTab.use(block: (DataTab) -> Unit) = try { block(this) } finally { close() }
    private fun frames() = repeat(4) { rule.mainClock.advanceTimeByFrame() }

    private val row1 = "(1, '2026-01-31 10:20:30', '2026-01-31 10:20:30 +01:00', '2026-01-31 10:20', '2026-01-31', '10:20:30', 1, N'x')"

    @Test fun theExplorerReadsTheServerThroughTheGenericDialect() {
        Introspector(ds, password).use { i ->
            val spaces = i.namespaces().map { it.label }
            assertTrue("dbo" in spaces, spaces.toString())
            assertTrue(spaces.none { it.equals("sys", true) || it.equals("INFORMATION_SCHEMA", true) }, spaces.toString())
            val tables = i.objects(Namespace(null, "dbo")).map { it.name }
            assertTrue("ev" in tables && "auto" in tables, tables.toString())
            val d = i.details(DbObject(Namespace(null, "dbo"), "ev", DbObjectKind.TABLE))
            assertEquals(listOf("id"), d.primaryKey)
            assertEquals(8, d.columns.size)
        }
    }

    @Test fun theDriverReportsTheColumnTypesThePickerNeeds() {
        reset(row1)
        open().use { tab ->
            val types = tab.grid.result.columns.map { it.typeName }
            assertEquals(
                listOf(null, TemporalKind.DATETIME, TemporalKind.DATETIME_TZ, TemporalKind.DATETIME, TemporalKind.DATE, TemporalKind.TIME, null, null),
                types.map { temporalKind(it) }, types.toString(),
            )
        }
    }

    @Test fun whatTheGridShowsIsReadBackByThePicker() {
        reset(row1)
        open().use { tab ->
            val r = tab.grid.rows[0]
            val dt = parseMoment(r[1].toString(), TemporalKind.DATETIME)
            assertEquals("2026-01-31 10:20:30", dt?.let { formatMoment(it, TemporalKind.DATETIME) }, r[1].toString())
            val tz = parseMoment(r[2].toString(), TemporalKind.DATETIME_TZ)
            assertEquals("2026-01-31 10:20:30+01:00", tz?.let { formatMoment(it, TemporalKind.DATETIME_TZ) }, r[2].toString())
            assertEquals("2026-01-31", formatMoment(parseMoment(r[4].toString(), TemporalKind.DATE)!!, TemporalKind.DATE))
            assertEquals("10:20:30", formatMoment(parseMoment(r[5].toString(), TemporalKind.TIME)!!, TemporalKind.TIME), r[5].toString())
        }
    }

    @Test fun pickedValuesAreStoredWithTheirOffsets() {
        reset(row1)
        open().use { tab ->
            tab.editor.setCell(0, 1, CellValue.Text("2026-12-24 23:59:58"), tab.grid)
            tab.editor.setCell(0, 2, CellValue.Text(formatMoment(parseMoment("2026-02-20 14:05:09+05:30", TemporalKind.DATETIME_TZ)!!, TemporalKind.DATETIME_TZ)), tab.grid)
            tab.editor.setCell(0, 3, CellValue.Text("2026-12-24 08:15:00"), tab.grid)
            tab.editor.setCell(0, 4, CellValue.Text("2026-02-28"), tab.grid)
            tab.editor.setCell(0, 5, CellValue.Text("07:05:03"), tab.grid)
            tab.editor.setCell(0, 6, CellValue.Text("false"), tab.grid)
            assertNull(tab.submit())
            val r = stored("select convert(varchar(19), dt, 120), dtz, convert(varchar(16), sdt, 120), convert(varchar(10), d, 23), convert(varchar(8), tm, 108), flag from ev").single()
            assertEquals(listOf<Any?>("2026-12-24 23:59:58", "2026-02-20 14:05:09.0000000 +05:30", "2026-12-24 08:15", "2026-02-28", "07:05:03", "0"), r)
        }
    }

    @Test fun anInsertedRowAndNullsWork() {
        reset()
        open().use { tab ->
            tab.editor.addRow(8)
            val row = tab.grid.rows.size
            tab.editor.setCell(row, 0, CellValue.Text("5"), tab.grid)
            tab.editor.setCell(row, 1, CellValue.Text("2026-03-08 23:30:00"), tab.grid)
            tab.editor.setCell(row, 2, CellValue.Text("2026-03-08 23:30:00-03:00"), tab.grid)
            tab.editor.setCell(row, 6, CellValue.Text("true"), tab.grid)
            tab.editor.setCell(row, 7, CellValue.Text("ñandú"), tab.grid)
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("2026-03-08 23:30:00", "2026-03-08 23:30:00.0000000 -03:00", null, "1", "ñandú"), stored("select convert(varchar(19), dt, 120), dtz, d, flag, note from ev").single().let { listOf(it[0], it[1], it[2], it[3], it[4]) })
        }
    }

    @Test fun identityColumnsTakeTheirDefaultsOnInsert() {
        DriverManager.getConnection(ds.url, ds.user, password).use { it.createStatement().execute("delete from auto") }
        open("auto").use { tab ->
            tab.editor.addRow(2)
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("7"), stored("select n from auto").single())
        }
    }

    @Test fun theCalendarEditsADatetimeoffsetCellEndToEnd() {
        reset(row1)
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.DARK) { Grid(tab.grid, {}, Modifier.size(700.dp, 420.dp), editing = tab.editor) } }
            frames()
            rule.onNodeWithTag("cell:0:2").performMouseInput { doubleClick() }
            frames()
            rule.onNodeWithTag("date-picker").assertExists()
            rule.onNodeWithTag("offset").assertTextContains("UTC+01:00")
            rule.onNodeWithTag("day:2026-01-12").performClick(); frames()
            rule.onNodeWithTag("h+").performClick(); frames()
            repeat(4) { rule.onNodeWithTag("offset+").performClick(); frames() }
            rule.onNodeWithTag("offset").assertTextContains("UTC+02:00")
            rule.onNodeWithTag("date-ok").performClick(); frames()
            assertNull(tab.submit())
            assertEquals("2026-01-12 11:20:30.0000000 +02:00", stored("select dtz from ev").single().single())
        }
    }

    @Test fun theCalendarEditsADatetime2AndADateCell() {
        reset(row1)
        open().use { tab ->
            rule.mainClock.autoAdvance = false
            rule.setContent { ksqlc.app.ui.KsqlcTheme(ThemeMode.LIGHT) { Grid(tab.grid, {}, Modifier.size(900.dp, 420.dp), editing = tab.editor) } }
            frames()
            rule.onNodeWithTag("cell:0:4").performMouseInput { doubleClick() }; frames()
            rule.onNodeWithTag("day:2026-01-05").performClick(); frames()
            rule.onNodeWithTag("cell:0:1").performMouseInput { doubleClick() }; frames()
            rule.onNodeWithTag("offset").assertDoesNotExist()
            rule.onNodeWithTag("m+").performClick(); frames()
            rule.onNodeWithTag("date-ok").performClick(); frames()
            assertNull(tab.submit())
            assertEquals(listOf<Any?>("2026-01-05", "2026-01-31 10:21:30"), stored("select convert(varchar(10), d, 23), convert(varchar(19), dt, 120) from ev").single())
        }
    }

    /** A login whose language reads 'yyyy-mm-dd' as year-day-month: untyped text would store 5 January as 1 May. */
    @Test fun datesDoNotDependOnTheLoginsLanguage() {
        reset(row1)                                                   // before the language changes: its literals are ISO text
        DriverManager.getConnection(ds.url, ds.user, password).use { it.createStatement().execute("alter login ${ds.user} with default_language = [British]") }
        try {
            open().use { tab ->
                tab.editor.setCell(0, 1, CellValue.Text("2026-01-05 10:20:30"), tab.grid)           // datetime2
                tab.editor.setCell(0, 2, CellValue.Text("2026-01-05 10:20:30+01:00"), tab.grid)    // datetimeoffset
                tab.editor.setCell(0, 3, CellValue.Text("2026-01-05 10:20:00"), tab.grid)          // smalldatetime
                tab.editor.setCell(0, 4, CellValue.Text("2026-01-05"), tab.grid)                   // date
                assertNull(tab.submit())
                assertEquals(
                    listOf<Any?>("2026-01-05 10:20:30", "2026-01-05 10:20:30.0000000 +01:00", "2026-01-05 10:20", "2026-01-05"),
                    stored("select convert(varchar(19), dt, 120), dtz, convert(varchar(16), sdt, 120), convert(varchar(10), d, 23) from ev").single(),
                )
            }
        } finally {
            DriverManager.getConnection(ds.url, ds.user, password).use { it.createStatement().execute("alter login ${ds.user} with default_language = [us_english]") }
        }
    }
}
