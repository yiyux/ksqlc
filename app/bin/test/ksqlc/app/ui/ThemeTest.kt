package ksqlc.app.ui

import androidx.compose.ui.graphics.Color
import ksqlc.sql.TokenKind
import kotlin.math.pow
import kotlin.test.*

class ThemeTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float) = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = lum(a).let { x -> lum(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }
    private val themes = mapOf("oscuro" to DarkColors, "claro" to LightColors)

    @Test fun primaryTextIsReadableOnEverySurface() {
        for ((n, c) in themes) for ((surface, bg) in listOf("window" to c.windowBg, "editor" to c.editorBg, "header" to c.headerBg, "selection" to c.selection, "inactive" to c.selectionInactive)) {
            assertTrue(contrast(c.text, bg) >= 4.5, "$n: texto sobre $surface = ${contrast(c.text, bg)}")
        }
    }

    @Test fun secondaryTextAndCommentsAreStillLegible() {
        for ((n, c) in themes) {
            assertTrue(contrast(c.textSecondary, c.windowBg) >= 3.5, "$n: texto secundario/window = ${contrast(c.textSecondary, c.windowBg)}")
            assertTrue(contrast(c.textSecondary, c.editorBg) >= 4.5, "$n: texto secundario/editor = ${contrast(c.textSecondary, c.editorBg)}")
            assertTrue(contrast(c.textMuted, c.windowBg) >= 3.0, "$n: texto atenuado/window = ${contrast(c.textMuted, c.windowBg)}")
            assertTrue(contrast(c.synComment, c.editorBg) >= 3.0, "$n: comentario = ${contrast(c.synComment, c.editorBg)}")
            assertTrue(contrast(c.gutterTextCurrent, c.currentLine) >= 4.5, "$n: nº de línea actual = ${contrast(c.gutterTextCurrent, c.currentLine)}")
            assertTrue(contrast(c.nullText, c.editorBg) >= 4.0, "$n: <null> = ${contrast(c.nullText, c.editorBg)}")
            assertTrue(contrast(c.gridText, c.editorBg) >= 7.0, "$n: texto del grid = ${contrast(c.gridText, c.editorBg)}")
            assertTrue(contrast(c.gridText, c.selection) >= 4.5, "$n: texto sobre celda seleccionada = ${contrast(c.gridText, c.selection)}")
        }
    }

    @Test fun everySyntaxColourIsReadableOnTheEditorBackground() {
        for ((n, c) in themes) for (k in TokenKind.entries) {
            val min = if (k == TokenKind.COMMENT) 3.0 else 4.5
            assertTrue(contrast(c.syntax(k), c.editorBg) >= min, "$n: $k = ${contrast(c.syntax(k), c.editorBg)}")
        }
    }

    @Test fun accentButtonsKeepTheirLabelsReadable() {
        for ((n, c) in themes) assertTrue(contrast(c.onAccent, c.accent) >= 4.5, "$n: onAccent = ${contrast(c.onAccent, c.accent)}")
    }

    @Test fun errorTextIsReadableOnTheWindow() {
        for ((n, c) in themes) assertTrue(contrast(c.error, c.windowBg) >= 3.0, "$n: error = ${contrast(c.error, c.windowBg)}")
    }

    @Test fun dialogsAndLinksAreReadable() {
        for ((n, c) in themes) {
            assertTrue(contrast(c.text, c.dialogBg) >= 4.5, "$n: texto sobre diálogo = ${contrast(c.text, c.dialogBg)}")
            assertTrue(contrast(c.link, c.dialogBg) >= 4.5, "$n: enlace = ${contrast(c.link, c.dialogBg)}")
            assertTrue(contrast(c.text, c.toolbarBg) >= 4.5, "$n: texto sobre barra de acciones = ${contrast(c.text, c.toolbarBg)}")
        }
    }

    @Test fun lightAndDarkAreDistinct() {
        assertTrue(DarkColors.dark && !LightColors.dark)
        assertTrue(lum(DarkColors.editorBg) < 0.1 && lum(LightColors.editorBg) > 0.8)
    }
}
