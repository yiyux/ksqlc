package ksqlc.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.*
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SearchObjectsTest {
    private fun o(schema: String, name: String, kind: DbObjectKind = DbObjectKind.TABLE) = DbObject(Namespace(null, schema), name, kind)
    private val all = listOf(o("public", "product_orders"), o("sales", "orders"), o("public", "people"), o("orders_archive", "events"), o("public", "order_items", DbObjectKind.VIEW))

    @Test fun ranksPrefixesThenContainsThenSchemaMatches() {
        assertEquals(listOf("order_items", "orders", "product_orders", "events"), searchObjects(all, "order").map { it.name })
        assertEquals(listOf("order_items"), searchObjects(all, "public order_i").map { it.name })
        assertEquals(listOf("orders"), searchObjects(all, "SALES.ORD").map { it.name })
        assertEquals(emptyList(), searchObjects(all, "zzz").map { it.name })
        assertEquals(5, searchObjects(all, "").size)
        assertEquals(2, searchObjects(all, "", limit = 2).size)
    }
}

@OptIn(ExperimentalTestApi::class)
class ObjectSearchUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-s"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ss")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun searchesTablesAndOpensOrRevealsTheChosenOne() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        val sql = "create table people(id integer primary key); create table orders(id integer primary key); create table product_orders(id integer primary key)"
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.catalog?.all()?.size == 3 }

        rule.onNodeWithTag("object-search-open").performClick()
        until { exists("object-search-input") }
        rule.onNodeWithTag("object-search-input").performTextInput("ord")
        until { exists("object-hit:1") }
        rule.onNodeWithTag("object-hit:0").assertTextContains("orders", substring = true)
        rule.onNodeWithTag("object-search-input").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown); keyDown(Key.Enter); keyUp(Key.Enter) }
        until { state.dataTabs.size == 1 }
        assertEquals("product_orders", state.dataTabs[0].obj.name)
        until { !exists("object-search") }

        rule.onNodeWithTag("object-search-open").performClick()
        until { exists("object-search-input") }
        rule.onNodeWithTag("object-search-input").performTextInput("peo")
        until { exists("object-hit:0") }
        rule.onNodeWithTag("object-search-input").performKeyInput { keyDown(Key.ShiftLeft); keyDown(Key.Enter); keyUp(Key.Enter); keyUp(Key.ShiftLeft) }
        until { state.explorerSelectedId != null }
        assertNotNull(state.explorerSelectedId)
        assertEquals(1, state.dataTabs.size)      // revealing opens nothing
    }
}
