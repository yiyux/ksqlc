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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LoadMoreErrorTest {
    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-l"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-ls")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    @Test fun aReadThatBreaksOnALaterPageEndsTheGridAndSaysWhy() {
        rule.setContent { App(state) }
        state.form.database = Files.createTempFile("ksqlc-ui", ".db").also { it.toFile().deleteOnExit() }.toString()
        // row 700 overflows an integer: SQLite only notices when it steps to it, after rows 501..699 were read
        val sql = "with recursive n(i) as (select 1 union all select i + 1 from n where i < 700) select case when i = 700 then abs(-9223372036854775807 - 1) else i end as v from n"
        state.onEditorChange(TextFieldValue(sql, TextRange(sql.length)))
        rule.onNodeWithContentDescription("Ejecutar (", substring = true).performClick()
        val end = System.nanoTime() + 20_000_000_000
        while (state.running || state.tabs.size != 1) {
            check(System.nanoTime() < end) { "timeout esperando el resultado (status='${state.status}')" }
            Thread.sleep(50); rule.mainClock.advanceTimeByFrame()
        }
        val tab = state.tabs[0]
        assertEquals(PAGE, tab.rows.size)

        state.loadMore(tab)                         // reads 199 rows, then the read breaks: the rows already read are kept
        assertEquals(PAGE + 199, tab.rows.size)
        assertTrue(!tab.exhausted)

        state.loadMore(tab)                         // the next read raises the error, which must not escape
        assertTrue(state.status.startsWith("Error al leer más filas"), state.status)
        assertTrue(tab.exhausted)
        assertEquals(PAGE + 199, tab.rows.size)
    }
}
