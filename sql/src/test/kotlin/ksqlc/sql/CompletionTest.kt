package ksqlc.sql

import kotlin.test.*

class FakeSchema(
    override val complete: Boolean = true,
    private val data: Map<TableRef, List<ColumnRef>?> = mapOf(
        TableRef("main", "customers") to listOf(ColumnRef("id", "INTEGER"), ColumnRef("name", "TEXT")),
        TableRef("main", "orders") to listOf(ColumnRef("id", "INTEGER"), ColumnRef("customer_id", "INTEGER"), ColumnRef("total", "REAL")),
        TableRef("main", "v_open", isView = true) to null,
    ),
) : SchemaInfo {
    override fun schemas() = listOf("main")
    override fun tables() = data.keys.toList()
    override fun columns(table: TableRef) = data[table]
}

class CompletionTest {
    private val schema = FakeSchema()

    /** Context at the '|' in [marked]. */
    private fun ctx(marked: String): Pair<String, CompletionContext?> {
        val at = marked.indexOf('|'); val sql = marked.replace("|", "")
        return sql to completionContext(sql, at)
    }

    private fun names(marked: String): List<String> {
        val (sql, c) = ctx(marked)
        val range = statementAt(sql, marked.indexOf('|').coerceAtMost(sql.length))
        val refs = range?.let { tableRefs(sql, it) } ?: emptyList()
        return suggest(c!!, schema, refs).map { it.text }
    }

    @Test fun slotAfterFromIsTable() {
        val c = ctx("select * from cu|").second!!
        assertEquals(Slot.TABLE, c.slot); assertEquals("cu", c.prefix)
        assertEquals(listOf("customers"), names("select * from cu|"))
    }

    @Test fun tablesAndSchemasRightAfterFrom() {
        val n = names("select * from |")
        assertTrue(n.containsAll(listOf("customers", "orders", "v_open", "main")))
    }

    @Test fun aliasQualifierListsThatTablesColumns() {
        assertEquals(listOf("customer_id", "id", "total").sorted(), names("select o.| from customers c join orders o on 1=1").sorted())
        assertEquals(listOf("id", "name"), names("select c.| from customers c join orders o on 1=1").sorted())
    }

    @Test fun columnSlotOffersColumnsOfTheStatementsTablesThenKeywords() {
        val n = names("select | from customers")
        assertTrue("name" in n && "ADD" in n)
        assertTrue(n.indexOf("name") < n.indexOf("ADD"))
        assertFalse("total" in n)
    }

    @Test fun whereAfterOperatorIsColumnSlot() = assertEquals(Slot.COLUMN, ctx("select 1 from customers where id = |").second!!.slot)

    @Test fun afterTableNameKeywordsAreOffered() {
        val c = ctx("select * from customers wh|").second!!
        assertEquals(Slot.KEYWORD, c.slot)
        assertTrue("WHERE" in names("select * from customers wh|"))
    }

    @Test fun schemaQualifierListsItsTables() = assertEquals(listOf("customers", "orders", "v_open"), names("select * from main.|").sorted())

    @Test fun commaInFromListIsStillATableSlot() = assertEquals(Slot.TABLE, ctx("select * from customers c, |").second!!.slot)

    @Test fun nothingInsideStringsOrLineComments() {
        assertNull(ctx("select 'ab|c'").second)
        assertNull(ctx("select 1 -- ab|").second)
    }

    @Test fun secondStatementIgnoresTheFirst() = assertEquals(Slot.TABLE, ctx("select 1 from a; select * from |").second!!.slot)

    @Test fun containsMatchesComeAfterPrefixMatches() {
        val n = names("select * from |er")
        assertEquals(listOf("orders"), n.filter { it == "orders" })
    }

    @Test fun tableRefsFindAliasesCtesAndSkipFunctions() {
        val sql = "with x as (select 1) select * from customers as c, main.orders o join x on 1=1 where extract(year from d) > 1"
        val r = tableRefs(sql, statementAt(sql, 0)!!)
        assertEquals(listOf("x", "customers", "orders", "x"), r.map { it.name })
        assertEquals(listOf("c", "o"), r.filter { !it.isCte }.mapNotNull { it.alias })
        assertEquals("main", r.first { it.name == "orders" }.schema)
        assertTrue(r.first().isCte)
    }

    @Test fun identifierAtFindsTheDottedChain() {
        val sql = "select main.orders.total from x"
        val ch = identifierAt(sql, sql.indexOf("orders") + 2)!!
        assertEquals(listOf("main", "orders", "total"), ch.parts); assertEquals(1, ch.index)
        assertEquals("main.orders.total", sql.substring(ch.start, ch.end))
        assertNull(identifierAt(sql, sql.indexOf("select") + 2))
    }
}
