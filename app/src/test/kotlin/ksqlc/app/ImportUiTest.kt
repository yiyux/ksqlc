package ksqlc.app

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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ImportUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-i"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-is")),
    )
    private val people = DbObject(Namespace(null, "main"), "people", DbObjectKind.TABLE)

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout esperando la condición (status='${state.status}')" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeByFrame()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun setEditor(sql: String) = state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
    private fun connectWithTable() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        setEditor("create table people(id integer primary key, name text not null, born text);")
        rule.onNodeWithContentDescription("Ejecutar script", substring = true).performClick()
        until { !state.running && state.explorer != null }
    }
    private fun count(): Int {
        state.run(listOf("select count(*) from people")); until { !state.running }
        return state.tabs.single().rows.single().single().toString().toInt()
    }

    @Test fun importsACsvMappingColumnsByHeaderAndReloadsTheOpenTab() {
        connectWithTable()
        state.openData(people); until { state.dataTabs.size == 1 }

        val csv = Files.createTempFile("ksqlc-import", ".csv")
        Files.writeString(csv, "NAME;ID;ignorada\nAda;1;x\n\"Grace; G.\";2;y\n")
        state.openImport(people); until { state.importJob != null }
        rule.onNodeWithTag("import-path").performTextInput(csv.toString())
        rule.onNodeWithTag("import-sep:1").performClick()
        until { state.importJob!!.preview.size == 3 }
        assertEquals(listOf<Int?>(1, 0, null), state.importJob!!.mapping.toList())

        rule.onNodeWithTag("import-run").performClick()
        until { state.importJob == null }
        assertTrue(state.log.any { "2 filas importadas en people" in it }, state.log.toString())
        until { state.dataTabs[0].grid.rows.size == 2 }
        assertEquals(2, count())
    }

    @Test fun aBadRowIsReportedAndNothingIsInserted() {
        connectWithTable()
        val csv = Files.createTempFile("ksqlc-import", ".csv")
        Files.writeString(csv, "id,name\n1,Ada\nx,Grace\n")
        state.openImport(people); until { state.importJob != null }
        rule.onNodeWithTag("import-path").performTextInput(csv.toString())
        until { state.importJob!!.preview.size == 3 }
        rule.onNodeWithTag("import-run").performClick()
        until { state.importJob?.error != null }
        assertTrue("fila 3, columna id" in state.importJob!!.error!!, state.importJob!!.error)
        assertEquals(0, count())
    }
}
