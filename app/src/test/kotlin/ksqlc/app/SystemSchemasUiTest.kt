package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SystemSchemasUiTest {
    @get:Rule val rule = createComposeRule()

    private val settingsDir = Files.createTempDirectory("ksqlc-ui-sys")
    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-sysc"), InMemorySecretStore()),
        SettingsStore(settingsDir),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun names() = state.catalog?.all()?.map { it.name }.orEmpty().sorted()

    @Test fun theExplorerButtonShowsAndHidesTheEnginesOwnObjects() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        val sql = "create table counted(id integer primary key autoincrement)"
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && names() == listOf("counted") }

        rule.onNodeWithTag("system-schemas").performClick()
        until { names() == listOf("counted", "sqlite_sequence") }
        assertEquals(true, state.settings.showSystemSchemas)
        assertEquals(true, SettingsStore(settingsDir).load().showSystemSchemas)      // remembered

        rule.onNodeWithTag("system-schemas").performClick()
        until { names() == listOf("counted") }
        assertEquals(false, state.settings.showSystemSchemas)
    }
}
