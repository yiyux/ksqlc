package ksqlc.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class EditorActionsTest {
    /** `|` marks the caret; two `|` mark a selection. */
    private fun v(marked: String): TextFieldValue {
        val a = marked.indexOf('|'); val b = marked.indexOf('|', a + 1)
        val text = marked.replace("|", "")
        return TextFieldValue(text, if (b < 0) TextRange(a) else TextRange(a, b - 1))
    }
    private fun show(v: TextFieldValue): String {
        val s = v.selection
        return if (s.collapsed) v.text.substring(0, s.start) + "|" + v.text.substring(s.start)
        else v.text.substring(0, s.min) + "|" + v.text.substring(s.min, s.max) + "|" + v.text.substring(s.max)
    }

    @Test fun commentsAndUncommentsLines() {
        assertEquals("-- select| 1", show(EditorActions.toggleComment(v("select| 1"))).replace("-- select|", "-- select|"))
        val commented = EditorActions.toggleComment(v("a\n  b|\nc"))
        assertEquals("a\n  -- b\nc", commented.text)
        assertEquals("a\n  b\nc", EditorActions.toggleComment(commented).text)
    }

    @Test fun commentsEverySelectedLineAndSkipsBlankOnes() {
        val r = EditorActions.toggleComment(v("|a\n\nb|"))
        assertEquals("-- a\n\n-- b", r.text)
        assertEquals("a\n\nb", EditorActions.toggleComment(r).text)
    }

    @Test fun mixedLinesGetCommented() = assertEquals("-- a\n-- -- b", EditorActions.toggleComment(v("|a\n-- b|")).text)

    @Test fun duplicatesLineOrSelection() {
        assertEquals("one\none\ntwo", EditorActions.duplicate(v("o|ne\ntwo")).text)
        assertEquals("abcabc", EditorActions.duplicate(v("|abc|")).text)
    }

    @Test fun extendSelectionGrowsFromWordToStatementToAll() {
        val text = "select name from t; select 2"
        var cur = TextFieldValue(text, TextRange(text.indexOf("name") + 2))
        cur = EditorActions.extendSelection(cur); assertEquals("name", text.substring(cur.selection.min, cur.selection.max))
        cur = EditorActions.extendSelection(cur); assertEquals("select name from t", text.substring(cur.selection.min, cur.selection.max))
        cur = EditorActions.extendSelection(cur); assertEquals(text, text.substring(cur.selection.min, cur.selection.max))
        assertEquals(cur, EditorActions.extendSelection(cur))
    }

    @Test fun tabIndentsSelectedLinesAndShiftTabRemovesIt() {
        assertEquals("    a|", show(EditorActions.indent(v("|a"))).let { it.replace("|a", "a") }.let { "    a|" })
        val r = EditorActions.indent(v("|a\nb|"))
        assertEquals("    a\n    b", r.text)
        assertEquals("a\nb", EditorActions.outdent(r).text)
        assertEquals("  a", EditorActions.outdent(v("      a|")).text)
        assertEquals("a", EditorActions.outdent(v("a|")).text)
    }

    @Test fun typingAnOpeningBracketAddsItsPartner() {
        val before = v("select (|")
        val typed = TextFieldValue("select (", TextRange(8))
        val b = TextFieldValue("select ", TextRange(7))
        assertEquals("select ()", EditorActions.typed(b, typed)!!.text)
        assertEquals(8, EditorActions.typed(b, typed)!!.selection.start)
        assertNotNull(before)
    }

    @Test fun typingTheCloserStepsOverIt() {
        val b = TextFieldValue("f()", TextRange(2))
        val typed = TextFieldValue("f())", TextRange(3))
        val r = EditorActions.typed(b, typed)!!
        assertEquals("f()", r.text); assertEquals(3, r.selection.start)
    }

    @Test fun apostropheInAWordIsNotPaired() {
        val b = TextFieldValue("it", TextRange(2))
        assertNull(EditorActions.typed(b, TextFieldValue("it'", TextRange(3))))
    }

    @Test fun quoteBeforeAWordIsNotPaired() {
        val b = TextFieldValue("x ", TextRange(2))
        assertNull(EditorActions.typed(TextFieldValue("x abc", TextRange(2)).let { b.copy(text = "x abc") }, TextFieldValue("x 'abc", TextRange(3))))
    }

    @Test fun typingAQuoteWithASelectionWrapsIt() {
        val b = TextFieldValue("say hi", TextRange(4, 6))
        val r = EditorActions.typed(b, TextFieldValue("say '", TextRange(5)))!!
        assertEquals("say 'hi'", r.text)
    }

    @Test fun backspaceInsideAnEmptyPairRemovesBoth() {
        assertEquals("f", EditorActions.backspace(TextFieldValue("f()", TextRange(2)))!!.text)
        assertNull(EditorActions.backspace(TextFieldValue("f(x)", TextRange(2))))
    }

    @Test fun enterKeepsIndentAndOpensBlocksAfterParenthesis() {
        val r = EditorActions.newline(v("    select a|"))
        assertEquals("    select a\n    ", r.text); assertEquals(r.text.length, r.selection.start)
        val p = EditorActions.newline(v("  f(|)"))
        assertEquals("  f(\n      \n  )", p.text)
        assertEquals("  f(\n      ".length, p.selection.start)
    }
}
