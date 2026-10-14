package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.drivers.CellValue
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConsoleResultEditUiTest {
    @get:Rule val rule = createComposeRule()

    private val db = Files.createTempFile("ksqlc-ui-edit", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-ce"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ces")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun run(sql: String, tabs: Int) {
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.tabs.size == tabs }
    }
    private fun names() = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().executeQuery("select name from people order by id").let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test fun aPlainSelectIsEditableAndSendsItsChanges() {
        rule.setContent { App(state) }
        state.form.database = db
        run("create table people(id integer primary key, name text); insert into people values (1, 'Ada'), (2, 'Grace')", 0)
        until { state.catalog?.tables()?.isNotEmpty() == true }
        run("select id as k from people", 1)
        repeat(20) { rule.mainClock.advanceTimeByFrame() }
        assertNull(state.tabs[0].edit)                                     // an aliased column: not safe to write back

        run("select * from people where id > 0", 1)
        until { state.tabs[0].edit != null }
        val tab = state.tabs[0]; val data = assertNotNull(tab.edit)
        rule.onNodeWithContentDescription("Enviar cambios").assertIsNotEnabled()
        data.editor.setCell(1, 1, CellValue.Text("Hopper"), data.grid)
        until { data.editor.hasChanges }
        rule.onNodeWithContentDescription("Enviar cambios").assertIsEnabled().performClick()
        until { !data.editor.hasChanges && !data.loading }
        assertEquals(listOf("Ada", "Hopper"), names())
        assertEquals("Hopper", tab.shown.rows[1][1])                       // re-read through the editing connection

        // running something else while changes are pending asks first
        data.editor.setCell(0, 1, CellValue.Text("Lovelace"), data.grid)
        state.onEditorChange(TextFieldValue("select 1", TextRange(8)))
        state.runSelectionOrCurrent()
        until { state.confirmation != null }
        assertTrue(state.tabs[0] === tab)
    }
}
