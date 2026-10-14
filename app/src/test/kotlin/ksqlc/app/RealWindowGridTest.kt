package ksqlc.app

import androidx.compose.ui.awt.ComposeWindow
import ksqlc.core.*
import ksqlc.drivers.CellValue
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import org.junit.Assume.assumeFalse
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.sql.DriverManager
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grid in a real AWT window, driven by real mouse and key events (the Compose test rule cannot type characters).
 * Skipped where there is no display.
 */
class RealWindowGridTest {
    private val file = Files.createTempFile("ksqlc-rw", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"

    private class Harness(val win: ComposeWindow, val layer: Component) {
        fun mouse(id: Int, x: Int, y: Int, clicks: Int) = SwingUtilities.invokeAndWait {
            val button = if (id == MouseEvent.MOUSE_ENTERED || id == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
            layer.dispatchEvent(MouseEvent(layer, id, System.currentTimeMillis(), if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0, x, y, clicks, false, button))
        }
        fun click(x: Int, y: Int, clicks: Int = 1) {
            mouse(MouseEvent.MOUSE_ENTERED, x, y, 0); mouse(MouseEvent.MOUSE_MOVED, x, y, 0); Thread.sleep(30)
            mouse(MouseEvent.MOUSE_PRESSED, x, y, clicks); mouse(MouseEvent.MOUSE_RELEASED, x, y, clicks); mouse(MouseEvent.MOUSE_CLICKED, x, y, clicks)
        }
        fun doubleClick(x: Int, y: Int) { click(x, y, 1); Thread.sleep(120); click(x, y, 2); Thread.sleep(600) }
        fun key(id: Int, code: Int, ch: Char) = SwingUtilities.invokeAndWait {
            val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: layer
            target.dispatchEvent(KeyEvent(target, id, System.currentTimeMillis(), 0, code, ch))
        }
        fun press(code: Int, ch: Char) { key(KeyEvent.KEY_PRESSED, code, ch); key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ch); key(KeyEvent.KEY_RELEASED, code, ch); Thread.sleep(80) }
        fun type(text: String) = text.forEach { press(KeyEvent.getExtendedKeyCodeForChar(it.code), it) }
    }

    private fun find(c: Component): Component? {
        if (c is Container) for (k in c.components) find(k)?.let { return it }
        return if (c.javaClass.name.startsWith("org.jetbrains.skiko.SkiaLayer")) c else null
    }

    private fun withGrid(block: (Harness, DataTab) -> Unit) {
        assumeFalse("sin pantalla: se omite el test con ventana real", GraphicsEnvironment.isHeadless())
        DriverManager.getConnection(url).use {
            it.createStatement().apply { execute("create table cfg(actu boolean, vers varchar(30), cuando datetime)"); execute("insert into cfg values (1, '1.00.0450', '2026-01-31 10:20:30')") }
        }
        val obj = DbObject(Namespace(null, "main"), "cfg", DbObjectKind.TABLE)
        val d = Dialect.of(DbKind.SQLITE)
        val session = Session(DataSource("t", "t", DbKind.SQLITE, url), null)
        val sql = d.selectAll(obj)
        val rows = session.runScript(listOf(sql)).single() as StatementResult.Rows
        val tab = DataTab(obj, ResultTab("cfg", rows).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.SQLITE, emptyList()))
        tab.editor.addRow(3)
        lateinit var win: ComposeWindow
        SwingUtilities.invokeAndWait {
            win = ComposeWindow()
            win.setSize(900, 400)
            win.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.LIGHT) { Grid(tab.grid, {}, editing = tab.editor) } }
            win.isVisible = true
        }
        try {
            Thread.sleep(2500)
            block(Harness(win, find(win)!!), tab)
        } finally { SwingUtilities.invokeAndWait { win.dispose() }; tab.close() }
    }

    // layout of the grid: 26dp header, 27dp rows (row 1 is the new one), 39dp numbers column, 112dp columns
    private fun cellX(col: Int) = 39 + 112 * col + 56
    private val newRowY = 26 + 27 + 13

    private fun text(tab: DataTab, col: Int) = (tab.editor.valueAt(1, col, tab.grid) as? CellValue.Text)?.text

    @Test fun doubleClickingTwoCellsOfAnInsertedRowLetsYouTypeInBoth() = withGrid { h, tab ->
        h.doubleClick(cellX(0), newRowY); h.type("false"); h.press(KeyEvent.VK_ENTER, '\n')
        h.doubleClick(cellX(1), newRowY); h.type("2.0.1"); h.press(KeyEvent.VK_ENTER, '\n')
        assertEquals("false" to "2.0.1", text(tab, 0) to text(tab, 1))
    }

    @Test fun typingStraightAfterSelectingACellStartsEditingIt() = withGrid { h, tab ->
        h.click(cellX(1), newRowY); Thread.sleep(500)          // only select it
        h.type("abc"); h.press(KeyEvent.VK_ENTER, '\n')
        assertEquals("abc", text(tab, 1))
    }

    @Test fun tabMovesOnToTheNextCellAndKeepsTyping() = withGrid { h, tab ->
        h.doubleClick(cellX(0), newRowY); h.type("x"); h.press(KeyEvent.VK_TAB, '\t'); h.type("y"); h.press(KeyEvent.VK_ENTER, '\n')
        assertEquals("x" to "y", text(tab, 0) to text(tab, 1))
    }

    @Test fun clickingInsideTheEditorDoesNotStopTheTyping() = withGrid { h, tab ->
        h.doubleClick(cellX(1), newRowY)
        h.click(cellX(1) - 20, newRowY); Thread.sleep(600)
        h.type("ok"); h.press(KeyEvent.VK_ENTER, '\n')
        assertEquals("ok", text(tab, 1))
    }

    @Test fun escapeCancelsWhatWasTyped() = withGrid { h, tab ->
        h.doubleClick(cellX(1), newRowY); h.type("zzz"); h.press(KeyEvent.VK_ESCAPE, '\u001b')
        assertEquals(null, text(tab, 1))
    }

    @Test fun clickingTheCalendarKeepsTheKeyboardInTheEditor() = withGrid { h, tab ->
        h.doubleClick(cellX(2), newRowY)                       // a datetime column: the picker opens under the cell
        val popupLeft = 39 + 112 * 2
        val dayX = popupLeft + 8 + 234 / 7 * 3 + 16            // a day in the middle of the first week row
        val dayY = 26 + 27 * 2 + 8 + 26 + 6 + 14 + 6 + 14 + 28 * 2
        h.click(dayX, dayY); Thread.sleep(500)
        h.press(KeyEvent.VK_ENTER, '\n')                      // the editor must still own the keyboard to commit
        val written = text(tab, 2)
        assert(written != null && Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(written)) { "se esperaba una fecha y hora, hay: $written" }
    }
}
