package ksqlc.app

import androidx.compose.ui.input.key.Key
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ServicesToggleUiTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-t"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ts")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun frames(n: Int = 5) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        frames(2)
    }

    @Test fun servicesComesBackFromTheStatusBarTheShortcutAndARun() {
        rule.setContent { App(state) }
        frames()
        rule.onNodeWithContentDescription("Ocultar Services").performClick(); frames()
        assertFalse(state.servicesVisible)
        rule.onNodeWithContentDescription("Ocultar Services").assertDoesNotExist()
        rule.onNodeWithTag("services-toggle").performClick(); frames()
        assertTrue(state.servicesVisible)
        rule.onNodeWithContentDescription("Ocultar Services").assertExists()

        rule.onRoot().performKeyInput { keyDown(Key.MetaLeft); keyDown(Key.Eight); keyUp(Key.Eight); keyUp(Key.MetaLeft) }; frames()
        assertFalse(state.servicesVisible)

        // running something shows its results again
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        state.onEditorChange(TextFieldValue("select 1", TextRange(8)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        until { !state.running && state.tabs.size == 1 }
        assertEquals(true, state.servicesVisible)
    }
}
