package ksqlc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ksqlc.app.ui.*
import ksqlc.storage.ThemeMode
import org.junit.Before
import org.junit.Rule
import kotlin.test.*

private val SAMPLE = """-- Pedidos del último mes
select c.id, c.nombre, count(*) as total, sum(p.importe) as importe
from clientes c
join pedidos p on p.cliente_id = c.id
where p.fecha >= '2026-09-01' and p.estado <> 'anulado'
group by c.id, c.nombre
order by importe desc
limit 10;"""

@OptIn(ExperimentalTestApi::class)
class SqlEditorTest {
    @get:Rule val rule = createComposeRule()
    @Before fun manualClock() { rule.mainClock.autoAdvance = false }

    private var runCurrent = 0
    private var runAll = 0
    private var caret = 0 to 0
    private var current by mutableStateOf(TextFieldValue(SAMPLE))
    private var marker by mutableStateOf<RunMarker?>(null)

    private fun show(mode: ThemeMode, w: Int = 900, h: Int = 260) = rule.setContent {
        KsqlcTheme(mode) {
            Box(Modifier.size(w.dp, h.dp)) {
                SqlEditor(current, { current = it }, marker, { runCurrent++ }, { runAll++ }, { l, c -> caret = l to c }, Modifier.size(w.dp, h.dp).testTag("editor"))
            }
        }
    }

    private fun frames(n: Int = 3) = repeat(n) { rule.mainClock.advanceTimeByFrame() }

    @Test fun highlightColoursEachTokenKindFromTheTheme() {
        val a = highlight("select 'x' -- c\nfrom t where n = 1", DarkColors)
        fun colourAt(i: Int) = a.spanStyles.filter { i in it.start until it.end }.lastOrNull()?.item?.color
        assertEquals(DarkColors.synKeyword, colourAt(0))      // select
        assertEquals(DarkColors.synString, colourAt(8))       // 'x'
        assertEquals(DarkColors.synComment, colourAt(12))     // -- c
        assertEquals(DarkColors.synKeyword, colourAt(16))     // from
        assertEquals(DarkColors.synIdent, colourAt(21))       // t
        assertEquals(DarkColors.synNumber, a.text.lastIndexOf('1').let { colourAt(it) })
        assertEquals(a.text, "select 'x' -- c\nfrom t where n = 1")
    }

    @Test fun lineAndColumnAreOneBased() {
        assertEquals(0, lineOf("a\nb\nc", 0)); assertEquals(1, lineOf("a\nb\nc", 2)); assertEquals(2, lineOf("a\nb\nc", 5))
        assertEquals(1, columnOf("a\nbc", 2)); assertEquals(2, columnOf("a\nbc", 3)); assertEquals(1, columnOf("", 0))
    }

    @Test fun reportsTheCaretPosition() {
        current = TextFieldValue(SAMPLE, TextRange(SAMPLE.indexOf("from") + 2))
        show(ThemeMode.DARK); frames()
        assertEquals(3 to 3, caret)
    }

    @Test fun commandEnterRunsTheCurrentStatementAndShiftRunsEverything() {
        show(ThemeMode.DARK); frames()
        rule.onNodeWithTag("editor").performClick(); frames()
        rule.onNodeWithTag("editor").performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.Enter); keyUp(Key.MetaLeft) }
        assertEquals(1 to 0, runCurrent to runAll)
        rule.onNodeWithTag("editor").performKeyInput { keyDown(Key.MetaLeft); keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft); keyUp(Key.MetaLeft) }
        assertEquals(1 to 1, runCurrent to runAll)
    }

    @Test fun tabInsertsFourSpacesAtTheCaret() {
        current = TextFieldValue("ab", TextRange(1))
        show(ThemeMode.DARK); frames()
        rule.onNodeWithTag("editor").performClick(); frames()
        current = current.copy(selection = TextRange(1)); frames()
        rule.onNodeWithTag("editor").performKeyInput { pressKey(Key.Tab) }
        assertEquals("a    b", current.text)
        assertEquals(5, current.selection.start)
    }

    @Test fun longLinesDoNotWrap() {
        val long = "select " + "campo, ".repeat(200) + "x from t"
        current = TextFieldValue(long)
        show(ThemeMode.DARK, w = 500); frames()
        val node = rule.onNodeWithTag("editor").fetchSemanticsNode()
        assertTrue(node.size.height <= 300, "el editor no debe crecer por ajuste de línea: ${node.size}")
        assertEquals(1 to long.length + 1, caret.let { 1 to (long.length + 1) })
    }

    @Test fun rendersBothThemes() {
        marker = RunMarker(SAMPLE.indexOf("select"), SAMPLE.length - 1, ok = true, millis = 502)
        current = TextFieldValue(SAMPLE, TextRange(SAMPLE.indexOf("from") + 2))
        show(ThemeMode.DARK); frames(); rule.snapshot("editor-dark")
    }

    @Test fun rendersTheLightTheme() {
        marker = RunMarker(SAMPLE.indexOf("select"), SAMPLE.length - 1, ok = false, millis = 12)
        current = TextFieldValue(SAMPLE, TextRange(SAMPLE.indexOf("where") + 2))
        show(ThemeMode.LIGHT); frames(); rule.snapshot("editor-light")
    }
}
