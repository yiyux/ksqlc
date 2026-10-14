package ksqlc.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ksqlc.core.*
import ksqlc.drivers.SchemaSource
import ksqlc.drivers.dialect.Dialect
import kotlin.test.*

private class FakeSource : SchemaSource {
    override val dialect = Dialect.of(DbKind.POSTGRES)
    val app = Namespace(null, "app")
    var failObjects = false
    var objectCalls = 0
    var extraTable: String? = null

    override fun namespaces() = listOf(app, Namespace(null, "public"))

    override fun objects(ns: Namespace): List<DbObject> {
        objectCalls++
        if (failObjects) error("boom")
        if (ns != app) return emptyList()
        return listOfNotNull(
            DbObject(ns, "customers", DbObjectKind.TABLE),
            DbObject(ns, "orders", DbObjectKind.TABLE),
            DbObject(ns, "big_orders", DbObjectKind.VIEW),
            DbObject(ns, "add(a integer)", DbObjectKind.ROUTINE),
            extraTable?.let { DbObject(ns, it, DbObjectKind.TABLE) },
        )
    }

    override fun details(o: DbObject) = if (o.name == "orders") TableDetails(
        columns = listOf(ColumnInfo("id", "int4", nullable = false), ColumnInfo("customer_id", "int4")),
        primaryKey = listOf("id"),
        indexes = listOf(IndexInfo("orders_customer_idx", false, listOf("customer_id"))),
        foreignKeys = listOf(ForeignKeyInfo("fk_c", listOf("customer_id"), app, "customers", listOf("id"))),
    ) else TableDetails(listOf(ColumnInfo("id", "int4")))

    override fun ddl(o: DbObject) = "-- ddl of ${o.name}"
}

class TreeModelTest {
    private val src = FakeSource()
    private val model = TreeModel(src, Dispatchers.Unconfined)

    private fun labels(filter: String = "") = visibleRows(model.roots, filter).map { "  ".repeat(it.depth) + it.node.label }
    private fun node(id: String): TreeNode {
        fun find(ns: List<TreeNode>): TreeNode? = ns.firstNotNullOfOrNull { if (it.id == id) it else it.children?.let(::find) }
        return find(model.roots) ?: error("sin nodo $id")
    }

    @Test fun loadShowsNamespacesCollapsed() = runBlocking<Unit> {
        model.load()
        assertEquals(listOf("app", "public"), labels())
        assertEquals(0, src.objectCalls)   // objects load lazily, on expand
    }

    @Test fun expandingANamespaceGroupsObjectsByKind() = runBlocking<Unit> {
        model.load()
        model.expand(node("ns:.app"))
        assertEquals(
            listOf("app", "Tablas (2)", "Vistas (1)", "Rutinas (1)", "public"),
            labels().map { it.trim() }.let { l -> l.filterNot { it in setOf("customers", "orders", "big_orders", "add(a integer)") } },
        )
        model.expand(node("ns:.app/Tablas"))
        assertTrue(labels().containsAll(listOf("    customers", "    orders")))
    }

    @Test fun emptyNamespaceExpandsToNothing() = runBlocking<Unit> {
        model.load()
        model.expand(node("ns:.public"))
        assertEquals(emptyList(), node("ns:.public").children)
        assertNull(node("ns:.public").error)
    }

    @Test fun expandingATableShowsColumnsIndexesAndForeignKeys() = runBlocking<Unit> {
        model.load()
        model.expand(node("ns:.app")); model.expand(node("ns:.app/Tablas")); model.expand(node("ns:.app/Tablas/orders"))
        val l = labels()
        assertTrue("      Columnas (2)" in l && "      Índices (1)" in l && "      Claves foráneas (1)" in l, l.toString())
        model.expand(node("ns:.app/Tablas/orders/cols"))
        assertEquals("int4  PK  not null", node("ns:.app/Tablas/orders/cols/id").detail)
        assertEquals("(customer_id) → customers(id)", node("ns:.app/Tablas/orders/fks/fk_c").detail)
    }

    @Test fun routinesAndSequencesAreLeaves() = runBlocking<Unit> {
        model.load(); model.expand(node("ns:.app")); model.expand(node("ns:.app/Rutinas"))
        assertFalse(node("ns:.app/Rutinas/add(a integer)").expandable)
    }

    @Test fun failureShowsErrorAndRetriesOnNextExpand() = runBlocking<Unit> {
        model.load()
        src.failObjects = true
        val app = node("ns:.app")
        model.expand(app)
        assertEquals("boom", app.error)
        assertNull(app.children)
        assertFalse(app.loading)
        src.failObjects = false
        model.expand(app)
        assertNull(app.error)
        assertNotNull(app.children)
    }

    @Test fun collapsedNodesHideTheirChildren() = runBlocking<Unit> {
        model.load(); model.expand(node("ns:.app"))
        node("ns:.app").expanded = false
        assertEquals(listOf("app", "public"), labels())
    }

    @Test fun filterKeepsMatchesAndTheirAncestorsOnly() = runBlocking<Unit> {
        model.load(); model.expand(node("ns:.app")); model.expand(node("ns:.app/Tablas")); model.expand(node("ns:.app/Vistas"))
        assertEquals(listOf("app", "  Tablas (2)", "    orders", "  Vistas (1)", "    big_orders"), labels("ORDERS"))
        assertEquals(emptyList(), labels("zzz"))
    }

    @Test fun refreshReloadsAndKeepsOpenNodesOpen() = runBlocking<Unit> {
        model.load(); model.expand(node("ns:.app")); model.expand(node("ns:.app/Tablas"))
        src.extraTable = "payments"
        model.refresh()
        val l = labels()
        assertTrue("    payments" in l, l.toString())
        assertTrue(node("ns:.app/Tablas").expanded)
    }

    @Test fun failedRefreshKeepsTheOldTree() = runBlocking<Unit> {
        var down = false
        val flaky = object : SchemaSource by src {
            override fun namespaces(): List<Namespace> = if (down) error("down") else src.namespaces()
        }
        val m = TreeModel(flaky, Dispatchers.Unconfined)
        m.load()
        val before = m.roots
        down = true
        assertFailsWith<IllegalStateException> { m.refresh() }
        assertSame(before, m.roots)
    }
}
