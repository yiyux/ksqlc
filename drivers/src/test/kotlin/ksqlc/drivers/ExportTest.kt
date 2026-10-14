package ksqlc.drivers

import ksqlc.core.*
import kotlin.test.*

class ExportTest {
    private val cols = listOf("id", "name", "note")
    private val rows = listOf(
        listOf<Any?>(1, "Ada", "dice \"hola\", y adiós"),
        listOf<Any?>(2, null, "dos\nlíneas"),
        listOf<Any?>(3, "O'Neil", java.math.BigDecimal("1.50")),
    )

    private fun write(f: ExportFormat, header: Boolean = true) = StringBuilder().also { sb ->
        RowWriter(f, cols, sb, table = "\"t\"", withHeader = header).apply { header(); rows.forEach(::row); finish() }
    }.toString()

    @Test fun csvQuotesOnlyWhenNeededAndNullIsEmpty() {
        assertEquals("id,name,note\n1,Ada,\"dice \"\"hola\"\", y adiós\"\n2,,\"dos\nlíneas\"\n3,O'Neil,1.50\n", write(ExportFormat.CSV))
        assertEquals("1,Ada,\"dice \"\"hola\"\", y adiós\"\n2,,\"dos\nlíneas\"\n3,O'Neil,1.50\n", write(ExportFormat.CSV, header = false))
    }

    @Test fun tsvUsesTabsAndLeavesCommasAlone() {
        val lines = write(ExportFormat.TSV).lines()
        assertEquals("id\tname\tnote", lines[0])
        assertEquals("1\tAda\t\"dice \"\"hola\"\", y adiós\"", lines[1])
        assertEquals("3\tO'Neil\t1.50", lines.last { it.isNotEmpty() })
    }

    @Test fun rowHeaderAndTransposeForDelimitedFormats() {
        val data = listOf(listOf<Any?>(1, "Ada"), listOf<Any?>(2, null))
        val cols = listOf("id", "name")
        assertEquals("1,1,Ada\n2,2,\n", renderExport(ExportFormat.CSV, cols, data, withHeader = false, rowHeader = true))
        assertEquals(",id,name\n1,1,Ada\n2,2,\n", renderExport(ExportFormat.CSV, cols, data, withHeader = true, rowHeader = true))
        assertEquals("1,2\nAda,\n", renderExport(ExportFormat.CSV, cols, data, withHeader = false, transpose = true))
        assertEquals("id,1,2\nname,Ada,\n", renderExport(ExportFormat.CSV, cols, data, withHeader = true, transpose = true))
        assertEquals(",1,2\nid,1,2\nname,Ada,\n", renderExport(ExportFormat.CSV, cols, data, withHeader = true, transpose = true, rowHeader = true))
        // JSON ignores both
        assertEquals(renderExport(ExportFormat.JSON, cols, data), renderExport(ExportFormat.JSON, cols, data, transpose = true, rowHeader = true))
    }

    @Test fun jsonKeepsNumbersAndNulls() {
        val json = write(ExportFormat.JSON)
        assertTrue("""{"id": 1, "name": "Ada", "note": "dice \"hola\", y adiós"}""" in json, json)
        assertTrue("""{"id": 2, "name": null, "note": "dos\nlíneas"}""" in json, json)
        assertTrue(""""note": 1.50}""" in json, json)
        assertTrue(json.startsWith("[") && json.endsWith("]\n"))
        assertEquals("[]\n", StringBuilder().also { RowWriter(ExportFormat.JSON, cols, it).apply { header(); finish() } }.toString())
    }

    @Test fun insertStatementsEscapeQuotes() {
        val sql = write(ExportFormat.SQL_INSERT).lines()
        assertEquals("insert into \"t\" (\"id\", \"name\", \"note\") values (1, 'Ada', 'dice \"hola\", y adiós');", sql[0])
        assertTrue(sql[1].contains("NULL"), sql[1])
        assertTrue(sql.last { it.isNotEmpty() }.contains("'O''Neil', 1.50"), sql.toString())
    }

    @Test fun exportsQueryResultsInBatchesAndCanBeCancelled() { Session(DataSource("t", "t", DbKind.SQLITE, "jdbc:sqlite::memory:"), null).use { s ->
        val sql = "with recursive c(x) as (select 1 union all select x+1 from c where x<2500) select x, 'v' || x as v from c"
        val sb = StringBuilder()
        val n = exportQuery(s, sql, { RowWriter(ExportFormat.CSV, it, sb) })
        assertEquals(2500, n)
        assertEquals(2501, sb.lines().size - 1)
        val sb2 = StringBuilder()
        var seen = 0L
        val m = exportQuery(s, sql, { RowWriter(ExportFormat.CSV, it, sb2) }, cancelled = { seen >= 1000 }, onProgress = { seen = it })
        assertEquals(1000, m)
        assertFailsWith<IllegalStateException> { exportQuery(s, "select * from nope", { RowWriter(ExportFormat.CSV, it, sb) }) }
    }
    }
}
