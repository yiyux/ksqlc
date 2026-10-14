package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.math.BigDecimal
import kotlin.test.*

class DmlTest {
    private val sqlite = Dialect.of(DbKind.SQLITE)
    private val mysql = Dialect.of(DbKind.MYSQL)
    private val obj = DbObject(Namespace(null, "main"), "items", DbObjectKind.TABLE)
    private val cols = listOf(Column("id", "INTEGER"), Column("name", "TEXT"), Column("price", "REAL"))
    private fun t(s: String) = CellValue.Text(s)

    private fun build(
        dialect: Dialect = sqlite, kind: DbKind = DbKind.SQLITE, pk: List<String> = listOf("id"),
        updates: List<RowEdit> = emptyList(), inserts: List<Map<String, CellValue>> = emptyList(), deletes: List<List<Any?>> = emptyList(),
    ) = buildDml(dialect, kind, obj, cols, pk, updates, inserts, deletes)

    @Test fun updateSetsOnlyChangedColumnsAndFindsTheRowByKey() {
        val r = build(updates = listOf(RowEdit(listOf(7L, "old", 1.5), mapOf("name" to t("new it's"), "price" to CellValue.Null))))
        assertEquals(1, r.size)
        assertTrue(r[0].sql.startsWith("update ") && r[0].sql.endsWith("set \"name\" = ?, \"price\" = ? where \"id\" = ?"), r[0].sql)
        assertEquals(listOf<Any?>("new it's", null, 7L), r[0].params)
        assertTrue(r[0].preview.endsWith("set \"name\" = 'new it''s', \"price\" = NULL where \"id\" = 7"), r[0].preview)
    }

    @Test fun insertOmitsDefaultColumns() {
        val r = build(inserts = listOf(mapOf("id" to CellValue.Default, "name" to t("x"), "price" to t("2.5"))))
        assertTrue(r[0].sql.endsWith("(\"name\", \"price\") values (?, ?)"), r[0].sql)
        assertEquals(listOf<Any?>("x", 2.5), r[0].params)
    }

    @Test fun insertWithoutAnyValueUsesTheDialectsEmptyInsert() {
        val all = mapOf("id" to CellValue.Default, "name" to CellValue.Default)
        assertTrue(build(inserts = listOf(all))[0].sql.endsWith("default values"))
        assertTrue(build(mysql, DbKind.MYSQL, inserts = listOf(all))[0].sql.endsWith("() values ()"))
    }

    @Test fun deleteUsesTheKeyAndCompositeKeysUseAll() {
        val r = build(deletes = listOf(listOf(3L, "a", 1.0)))
        assertTrue(r[0].sql.endsWith("where \"id\" = ?")); assertEquals(listOf<Any?>(3L), r[0].params)
        val c = build(pk = listOf("id", "name"), deletes = listOf(listOf(3L, "a", 1.0)))
        assertTrue(c[0].sql.endsWith("where \"id\" = ? and \"name\" = ?")); assertEquals(listOf<Any?>(3L, "a"), c[0].params)
    }

    @Test fun deletesGoFirstThenUpdatesThenInserts() {
        val r = build(
            updates = listOf(RowEdit(listOf(1L, "a", 1.0), mapOf("name" to t("b")))), inserts = listOf(mapOf("name" to t("c"))), deletes = listOf(listOf(2L, "x", 2.0)),
        )
        assertEquals(listOf("delete", "update", "insert"), r.map { it.sql.substringBefore(' ') })
    }

    @Test fun mysqlQuotesWithBackticks() = assertTrue(build(mysql, DbKind.MYSQL, deletes = listOf(listOf(1L, "a", 1.0)))[0].sql.contains("`id` = ?"))

    @Test fun anEditWithOnlyDefaultsIsSkippedAndNoKeyMeansNoUpdatesOrDeletes() {
        assertTrue(build(updates = listOf(RowEdit(listOf(1L, "a", 1.0), mapOf("name" to CellValue.Default)))).isEmpty())
        assertFailsWith<IllegalStateException> { build(pk = emptyList(), deletes = listOf(listOf(1L, "a", 1.0))) }
        assertEquals(1, build(pk = emptyList(), inserts = listOf(mapOf("name" to t("x")))).size)
    }

    @Test fun aBadValueNamesTheColumn() {
        val e = assertFailsWith<DmlException> { build(updates = listOf(RowEdit(listOf(1L, "a", 1.0), mapOf("price" to t("abc"))))) }
        assertEquals("price", e.column)
        assertContains(e.message!!, "abc")
    }

    @Test fun parseParamByType() {
        assertEquals(42L, parseParam(" 42 ", "INTEGER", DbKind.POSTGRES))
        assertEquals(5L, parseParam("5", "int4", DbKind.POSTGRES))
        assertEquals(java.math.BigInteger("18446744073709551615"), parseParam("18446744073709551615", "BIGINT UNSIGNED", DbKind.MYSQL))
        assertEquals(BigDecimal("12.50"), parseParam("12.50", "numeric", DbKind.POSTGRES))
        assertEquals(1.5, parseParam("1.5", "float8", DbKind.POSTGRES))
        assertEquals(true, parseParam("T", "bool", DbKind.POSTGRES))
        assertEquals(false, parseParam("0", "BOOLEAN", DbKind.POSTGRES))
        assertEquals(true, parseParam("true", "BIT", DbKind.MYSQL))      // MySQL reports tinyint(1) as BIT
        assertEquals(UntypedParam("101"), parseParam("101", "BIT", DbKind.POSTGRES))
        assertEquals(java.sql.Date.valueOf("2026-01-31"), parseParam("2026-01-31", "date", DbKind.POSTGRES))
        assertEquals(java.sql.Timestamp.valueOf("2026-01-31 10:20:30"), parseParam("2026-01-31 10:20:30", "timestamp", DbKind.POSTGRES))
        assertEquals(java.sql.Timestamp.valueOf("2026-01-31 00:00:00"), parseParam("2026-01-31", "datetime", DbKind.MYSQL))
        assertIs<java.time.OffsetDateTime>(parseParam("2026-01-31 10:20:30+01:00", "timestamptz", DbKind.POSTGRES))
        assertEquals(UntypedParam("3f2c"), parseParam("3f2c", "uuid", DbKind.POSTGRES))
        assertEquals("3f2c", parseParam("3f2c", "char(36)", DbKind.MYSQL))
    }

    @Test fun sqlServerTypesAreBoundAsTypedValues() {
        for (t in listOf("datetime2", "smalldatetime", "datetime")) assertEquals(java.sql.Timestamp.valueOf("2026-01-05 10:20:30"), parseParam("2026-01-05 10:20:30", t, DbKind.MSSQL), t)
        assertIs<java.time.OffsetDateTime>(parseParam("2026-01-05 10:20:30+01:00", "datetimeoffset", DbKind.MSSQL))
        assertEquals(true, parseParam("1", "bit", DbKind.MSSQL))
        assertEquals(7L, parseParam("7", "int identity", DbKind.MSSQL))
        assertEquals(BigDecimal("3.50"), parseParam("3.50", "smallmoney", DbKind.MSSQL))
        assertEquals("ñandú", parseParam("ñandú", "nvarchar", DbKind.MSSQL))
    }

    @Test fun parseParamEmptyIsNullExceptForText() {
        assertNull(parseParam("", "integer", DbKind.POSTGRES)); assertNull(parseParam("  ", "date", DbKind.MYSQL))
        assertEquals("", parseParam("", "text", DbKind.POSTGRES)); assertEquals("", parseParam("", "VARCHAR(20)", DbKind.MYSQL))
    }

    @Test fun sqliteKeepsDatesAsTypedAndNumbersAsNumbers() {
        assertEquals("2026-01-31 10:00", parseParam("2026-01-31 10:00", "DATETIME", DbKind.SQLITE))
        assertEquals(3L, parseParam("3", "NUMERIC", DbKind.SQLITE)); assertEquals(3.5, parseParam("3.5", "REAL", DbKind.SQLITE))
    }

    @Test fun invalidTextsAndBinaryColumnsAreRejected() {
        assertFailsWith<IllegalArgumentException> { parseParam("x", "integer", DbKind.POSTGRES) }
        assertFailsWith<IllegalArgumentException> { parseParam("maybe", "boolean", DbKind.POSTGRES) }
        assertFailsWith<IllegalArgumentException> { parseParam("2026-13-45", "date", DbKind.POSTGRES) }
        assertFailsWith<IllegalArgumentException> { parseParam("abc", "bytea", DbKind.POSTGRES) }
        assertTrue(isBinaryType("BLOB") && isBinaryType("varbinary(10)") && !isBinaryType("text"))
    }
}
