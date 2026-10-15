package ksqlc.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import ksqlc.sql.statementAt

/** Pure text edits the editor binds to keys. Each returns the new value, or null when the key should do its normal thing. */
object EditorActions {
    private const val INDENT = "    "

    private fun TextFieldValue.lineStart(off: Int) = text.lastIndexOf('\n', off - 1) + 1
    private fun TextFieldValue.lineEnd(off: Int) = text.indexOf('\n', off).let { if (it < 0) text.length else it }
    private fun TextFieldValue.with(text: String, sel: TextRange) = TextFieldValue(text, sel)

    /** First and last line (as offsets) covered by the selection; a selection ending at column 0 does not take that last line. */
    private fun TextFieldValue.lineSpan(): Pair<Int, Int> {
        val s = selection
        val end = if (!s.collapsed && s.max > s.min && text.getOrNull(s.max - 1) == '\n') s.max - 1 else s.max
        return lineStart(s.min) to lineEnd(end)
    }

    /** ⌘/ — comments every selected line, or uncomments them all when they already are. */
    fun toggleComment(v: TextFieldValue): TextFieldValue {
        val (from, to) = v.lineSpan()
        val lines = v.text.substring(from, to).split('\n')
        val code = lines.filter { it.isNotBlank() }
        val uncomment = code.isNotEmpty() && code.all { it.trimStart().startsWith("--") }
        val edited = lines.map { line ->
            when {
                line.isBlank() -> line
                uncomment -> { val at = line.indexOf("--"); line.removeRange(at, at + 2 + if (line.getOrNull(at + 2) == ' ') 1 else 0) }
                else -> { val ind = line.length - line.trimStart().length; line.substring(0, ind) + "-- " + line.substring(ind) }
            }
        }
        val body = edited.joinToString("\n")
        val delta = body.length - (to - from)
        val s = v.selection
        val firstShift = edited.first().length - lines.first().length
        return v.with(v.text.replaceRange(from, to, body), TextRange((s.start + firstShift).coerceAtLeast(from), (s.end + delta).coerceAtLeast(from)))
    }

    /** ⌘D — duplicates the selection, or the current line when nothing is selected. */
    fun duplicate(v: TextFieldValue): TextFieldValue {
        val s = v.selection
        if (!s.collapsed) {
            val part = v.text.substring(s.min, s.max)
            return v.with(v.text.substring(0, s.max) + part + v.text.substring(s.max), TextRange(s.max, s.max + part.length))
        }
        val a = v.lineStart(s.start); val b = v.lineEnd(s.start)
        val line = v.text.substring(a, b)
        return v.with(v.text.substring(0, b) + "\n" + line + v.text.substring(b), TextRange(s.start + line.length + 1))
    }

    /** ⌘W — grows the selection: word → statement → everything. */
    fun extendSelection(v: TextFieldValue): TextFieldValue {
        val t = v.text; val s = v.selection
        fun isWord(i: Int) = i in t.indices && (t[i].isLetterOrDigit() || t[i] == '_')
        val candidates = ArrayList<TextRange>()
        var a = s.min; var b = s.max
        while (isWord(a - 1)) a--
        while (isWord(b)) b++
        candidates += TextRange(a, b)
        statementAt(t, s.min)?.let { candidates += TextRange(it.start, it.end) }
        candidates += TextRange(0, t.length)
        val next = candidates.firstOrNull { it.min <= s.min && it.max >= s.max && (it.min < s.min || it.max > s.max) } ?: return v
        return v.with(t, next)
    }

    /** Tab with a selection spanning lines indents them all; otherwise inserts four spaces. */
    fun indent(v: TextFieldValue): TextFieldValue {
        val s = v.selection
        if (s.collapsed || !v.text.substring(s.min, s.max).contains('\n')) return v.with(v.text.replaceRange(s.min, s.max, INDENT), TextRange(s.min + INDENT.length))
        val (from, to) = v.lineSpan()
        val lines = v.text.substring(from, to).split('\n')
        val body = lines.joinToString("\n") { if (it.isBlank()) it else INDENT + it }
        val added = lines.count { it.isNotBlank() } * INDENT.length
        return v.with(v.text.replaceRange(from, to, body), TextRange(s.start + INDENT.length, s.end + added))
    }

    /** ⇧Tab — removes up to four spaces (or a tab) from the start of each selected line. */
    fun outdent(v: TextFieldValue): TextFieldValue {
        val (from, to) = v.lineSpan()
        val lines = v.text.substring(from, to).split('\n')
        val removed = lines.map { l -> if (l.startsWith("\t")) 1 else l.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length) }
        val body = lines.zip(removed).joinToString("\n") { (l, n) -> l.substring(n) }
        val s = v.selection
        fun shift(off: Int): Int {
            var lineFrom = from; var before = 0
            for ((i, l) in lines.withIndex()) {
                if (off <= lineFrom + l.length) return off - before - minOf(removed[i], (off - lineFrom).coerceAtLeast(0))
                before += removed[i]; lineFrom += l.length + 1
            }
            return off - before
        }
        return v.with(v.text.replaceRange(from, to, body), TextRange(shift(s.start), shift(s.end)))
    }

    private val PAIRS = mapOf('(' to ')', '\'' to '\'', '"' to '"', '`' to '`')

    /** Typing an opening bracket or quote adds its partner; typing a partner that is already next steps over it. Null = not applicable. */
    fun typed(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        val b = before.selection; val a = after.selection
        if (!a.collapsed || after.text.length != before.text.length - (b.max - b.min) + 1) return null
        val pos = a.start
        val ch = after.text.getOrNull(pos - 1) ?: return null
        if (after.text.substring(0, pos - 1) != before.text.substring(0, b.min)) return null
        val next = after.text.getOrNull(pos)
        val prevCh = after.text.getOrNull(pos - 2)
        // step over an existing closer
        if (b.collapsed && (ch == ')' || ch == '\'' || ch == '"' || ch == '`') && before.text.getOrNull(b.start) == ch) {
            return TextFieldValue(before.text, TextRange(b.start + 1))
        }
        val closer = PAIRS[ch] ?: return null
        if (!b.collapsed) {   // wrap the selection
            val inner = before.text.substring(b.min, b.max)
            return TextFieldValue(before.text.replaceRange(b.min, b.max, "$ch$inner$closer"), TextRange(b.min + 1, b.min + 1 + inner.length))
        }
        // a quote after a letter/digit (an apostrophe) or in front of one is just a quote
        if (ch != '(' && (prevCh?.isLetterOrDigit() == true || next?.isLetterOrDigit() == true)) return null
        if (ch == '(' && next != null && (next.isLetterOrDigit() || next == '_')) return null
        return TextFieldValue(after.text.substring(0, pos) + closer + after.text.substring(pos), TextRange(pos))
    }

    /** Backspace between an empty pair removes both. */
    fun backspace(v: TextFieldValue): TextFieldValue? {
        val s = v.selection
        if (!s.collapsed || s.start == 0 || s.start >= v.text.length) return null
        val open = v.text[s.start - 1]; val close = v.text[s.start]
        if (PAIRS[open] != close) return null
        return v.with(v.text.removeRange(s.start - 1, s.start + 1), TextRange(s.start - 1))
    }

    /** Enter keeps the current line's indentation, one level deeper after an opening parenthesis (and splits `()` onto three lines). */
    fun newline(v: TextFieldValue): TextFieldValue {
        val s = v.selection
        val a = v.lineStart(s.min)
        val lead = v.text.substring(a, s.min).takeWhile { it == ' ' || it == '\t' }
        val prev = v.text.substring(a, s.min).trimEnd().lastOrNull()
        val inside = prev == '(' && v.text.getOrNull(s.max) == ')'
        val extra = if (prev == '(') INDENT else ""
        val insert = "\n$lead$extra" + if (inside) "\n$lead" else ""
        val caret = s.min + 1 + lead.length + extra.length
        return v.with(v.text.replaceRange(s.min, s.max, insert), TextRange(caret))
    }
}
