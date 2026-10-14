package ksqlc.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import ksqlc.sql.*

/** Where the last executed statement sits in the text and how it went; cleared as soon as the text changes. */
data class RunMarker(val start: Int, val end: Int, val ok: Boolean, val millis: Long)

val EditorLineHeight = 22.dp
val EditorGutterWidth = 63.dp
private const val MARGIN_COLUMN = 120

private val EditorStyle = MonoStyle.copy(
    lineHeight = 22.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Text with one colour span per token. Offsets are unchanged, so a [VisualTransformation] can use it as is. */
fun highlight(text: String, c: KsqlcColors): AnnotatedString = buildAnnotatedString {
    append(text)
    for (t in tokenize(text)) {
        if (t.kind == TokenKind.WHITESPACE) continue
        addStyle(SpanStyle(color = c.syntax(t.kind)), t.start, t.end)
    }
}

/** 0-based line of [offset]. */
fun lineOf(text: String, offset: Int): Int {
    var n = 0
    for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') n++
    return n
}

/** 1-based column of [offset]. */
fun columnOf(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    return o - (text.lastIndexOf('\n', o - 1) + 1) + 1
}

/** Other uses of the name under the caret, as [start, end) pairs; empty unless the name appears at least twice. */
fun occurrencesOf(text: String, caret: Int): List<Pair<Int, Int>> {
    val chain = identifierAt(text, caret) ?: return emptyList()
    val name = chain.parts[chain.index].lowercase()
    val hits = tokenize(text).filter { it.kind == TokenKind.IDENT || it.kind == TokenKind.QUOTED_IDENT }
        .filter { unquote(it.text(text)).lowercase() == name }
    return if (hits.size >= 2) hits.map { it.start to it.end } else emptyList()
}

private object NoSchema : SchemaInfo {
    override val complete = false
    override fun schemas() = emptyList<String>()
    override fun tables() = emptyList<TableRef>()
    override fun columns(table: TableRef): List<ColumnRef>? = null
}

/**
 * What turns the editor into an assistant. All optional: without it the editor still highlights and runs.
 * [onEdit] receives results of editor actions (comment, duplicate) so the owner can offer undo for them;
 * it falls back to the plain value callback.
 */
class EditorSupport(
    val schema: SchemaInfo? = null,
    val schemaVersion: Int = 0,
    val diagnostics: List<Diagnostic> = emptyList(),
    val quote: (String) -> String = { "\"$it\"" },
    val onEdit: ((TextFieldValue) -> Unit)? = null,
    val onUndo: () -> Boolean = { false },
    val onGoto: (Int) -> Unit = {},
    val onShowDdl: (Int) -> Unit = {},
    val onFormat: () -> Unit = {},
)

private fun DrawScope.squiggle(color: Color, x1: Float, x2: Float, y: Float, step: Float, amp: Float) {
    if (x2 <= x1) return
    val p = Path().apply {
        moveTo(x1, y)
        var x = x1; var up = true
        while (x < x2) { x = minOf(x + step, x2); lineTo(x, if (up) y - amp else y); up = !up }
    }
    drawPath(p, color, style = Stroke(1f))
}

/**
 * SQL console editor drawn in the style of an IDE: line-number gutter with its own border, highlighted
 * current line, a guide at column 120, syntax colours, and an outline plus timing around the statement that last ran.
 * Lines never wrap; the editor scrolls horizontally instead, so a line number always matches one text line.
 * With [support] it also completes names, underlines problems, highlights the other uses of the name under the
 * caret, jumps to declarations (⌘B / ⌘-click) and offers the usual editing keys.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SqlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    marker: RunMarker?,
    onRunCurrent: () -> Unit,
    onRunAll: () -> Unit,
    onCaret: (line: Int, column: Int) -> Unit,
    modifier: Modifier = Modifier,
    support: EditorSupport = EditorSupport(),
) {
    val c = Ksqlc.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var completion by remember { mutableStateOf<CompletionState?>(null) }
    var wanted by remember { mutableStateOf(false) }    // completion was asked for at the caret; reopen when columns arrive

    val lineH = with(density) { EditorLineHeight.toPx() }
    val charW = remember(measurer) { measurer.measure("0", EditorStyle).size.width.toFloat() }
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val caretLine = lineOf(value.text, value.selection.end)
    val style = EditorStyle.copy(color = c.synIdent)
    val numberStyle = EditorStyle.copy(fontSize = 12.sp)
    val edit = support.onEdit ?: onValueChange

    val transformation = remember(c) {
        VisualTransformation { text -> TransformedText(highlight(text.text, c), OffsetMapping.Identity) }
    }
    val occurrences = remember(value.text, value.selection.end, value.selection.collapsed) {
        if (value.selection.collapsed) occurrencesOf(value.text, value.selection.end) else emptyList()
    }

    LaunchedEffect(value.text, value.selection) { onCaret(caretLine + 1, columnOf(value.text, value.selection.end)) }

    fun openCompletion(v: TextFieldValue) {
        val ctx = completionContext(v.text, v.selection.end)
        if (ctx == null || ctx.prefix.firstOrNull()?.isDigit() == true) { completion = null; return }
        val range = statementAt(v.text, v.selection.end)
        val refs = range?.let { tableRefs(v.text, it) }.orEmpty()
        val items = suggest(ctx, support.schema ?: NoSchema, refs)
        completion = if (items.isEmpty()) null else CompletionState(ctx, items, 0)
    }
    LaunchedEffect(support.schemaVersion) { if (wanted) openCompletion(value) }

    fun accept(s: Suggestion) {
        val st = completion ?: return
        val caret = value.selection.end
        val text = insertionFor(s, st.ctx, support.quote)
        onValueChange(TextFieldValue(value.text.replaceRange(st.ctx.replaceStart, caret, text), TextRange(st.ctx.replaceStart + text.length)))
        completion = null; wanted = false
    }

    fun handleChange(new: TextFieldValue) {
        val old = value
        val fixed = EditorActions.typed(old, new) ?: new
        onValueChange(fixed)
        if (fixed.text == old.text) {                        // only the caret or selection moved
            if (fixed.selection != old.selection) { completion = null; wanted = false }
            return
        }
        val caret = if (fixed.selection.collapsed) fixed.selection.start else -1
        val ch = if (caret > 0) fixed.text[caret - 1] else ' '
        val grewByOne = fixed.text.length == old.text.length + 1
        when {
            grewByOne && (ch.isLetterOrDigit() || ch == '_' || ch == '.') -> { wanted = true; openCompletion(fixed) }
            completion != null && fixed.text.length < old.text.length -> openCompletion(fixed)
            else -> { completion = null; wanted = false }
        }
    }

    val viewportDp = with(density) { viewport.width.toDp() }
    val selectionColors = remember(c) { TextSelectionColors(handleColor = c.accent, backgroundColor = c.selection) }

    Box(modifier.background(c.editorBg).onSizeChanged { viewport = it }) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = with(density) { viewport.height.toDp() })
                    .verticalScroll(vScroll)
                    .drawBehind {
                        if (value.selection.collapsed) drawRect(c.currentLine, Offset(0f, caretLine * lineH), Size(size.width, lineH))
                    },
            ) {
                // gutter: only the visible lines are drawn, so long scripts stay cheap
                Canvas(Modifier.width(EditorGutterWidth).height(EditorLineHeight * lines)) {
                    val first = (vScroll.value / lineH).toInt().coerceAtLeast(0)
                    val last = minOf(lines - 1, ((vScroll.value + viewport.height) / lineH).toInt() + 1)
                    val numberRight = 28.dp.toPx()
                    for (i in first..last) {
                        val r = measurer.measure((i + 1).toString(), numberStyle.copy(color = if (i == caretLine) c.gutterTextCurrent else c.gutterText))
                        drawText(r, topLeft = Offset(numberRight - r.size.width, i * lineH + (lineH - r.size.height) / 2))
                    }
                    for (d in support.diagnostics) {
                        val dl = lineOf(value.text, d.start)
                        if (dl in first..last) drawCircle(if (d.severity == Severity.ERROR) c.error else c.warning, 2.5.dp.toPx(), Offset(8.dp.toPx(), dl * lineH + lineH / 2))
                    }
                    marker?.let { m ->
                        val ml = lineOf(value.text, m.start)
                        val cx = 34.dp.toPx(); val cy = ml * lineH + lineH / 2
                        val col = if (m.ok) c.success else c.error
                        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                        if (m.ok) drawPath(Path().apply { moveTo(cx - 4.dp.toPx(), cy); lineTo(cx - 1.2.dp.toPx(), cy + 3.dp.toPx()); lineTo(cx + 4.5.dp.toPx(), cy - 3.5.dp.toPx()) }, col, style = stroke)
                        else { drawLine(col, Offset(cx - 3.dp.toPx(), cy - 3.dp.toPx()), Offset(cx + 3.dp.toPx(), cy + 3.dp.toPx()), stroke.width, StrokeCap.Round); drawLine(col, Offset(cx + 3.dp.toPx(), cy - 3.dp.toPx()), Offset(cx - 3.dp.toPx(), cy + 3.dp.toPx()), stroke.width, StrokeCap.Round) }
                    }
                    drawLine(c.marginGuide, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                }
                Box(
                    Modifier.weight(1f).horizontalScroll(hScroll).drawBehind {
                        val x = 3.dp.toPx() + MARGIN_COLUMN * charW
                        drawLine(c.marginGuide, Offset(x, 0f), Offset(x, size.height), 1f)
                    },
                ) {
                    BasicTextField(
                        value, ::handleChange,
                        Modifier.padding(start = 3.dp, end = 48.dp)
                            // the field fills the viewport, so a click anywhere in the editor focuses it
                            .heightIn(min = with(density) { viewport.height.toDp() })
                            .widthIn(min = (viewportDp - EditorGutterWidth - 51.dp).coerceAtLeast(0.dp))
                            .onPointerEvent(PointerEventType.Press) { e ->
                                if (e.keyboardModifiers.isMetaPressed || e.keyboardModifiers.isCtrlPressed) {
                                    val l = layout; val p = e.changes.firstOrNull()?.position
                                    if (l != null && p != null) support.onGoto(l.getOffsetForPosition(p))
                                }
                            }
                            .drawBehind {
                                val l = layout ?: return@drawBehind
                                val len = l.layoutInput.text.length
                                for ((a, b) in occurrences) if (b <= len) {
                                    val r = l.getPathForRange(a, b).getBounds()
                                    drawRoundRect(c.occurrence, Offset(r.left - 1.dp.toPx(), r.top + 2f), Size(r.width + 2.dp.toPx(), r.height - 4f), CornerRadius(3.dp.toPx()))
                                }
                                val m = marker
                                if (m != null && m.end <= len && m.end > m.start) {
                                    val b = l.getPathForRange(m.start, m.end).getBounds()
                                    val pad = 2.dp.toPx()
                                    drawRoundRect(
                                        if (m.ok) c.successOutline else c.error, Offset(b.left - pad, b.top + 1f), Size(b.width + 2 * pad, b.height - 2f),
                                        CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()),
                                    )
                                    val lastLine = l.getLineForOffset(m.end)
                                    val hint = measurer.measure("${m.millis} ms", EditorStyle.copy(color = c.inlineHint))
                                    drawText(hint, topLeft = Offset(l.getLineRight(lastLine) + 15.dp.toPx(), l.getLineTop(lastLine) + (lineH - hint.size.height) / 2))
                                }
                                for (d in support.diagnostics) {
                                    val s = d.start.coerceAtMost(len); val e = d.end.coerceAtMost(len)
                                    if (e <= s) continue
                                    val col = if (d.severity == Severity.ERROR) c.error else c.warning
                                    for (line in l.getLineForOffset(s)..l.getLineForOffset(maxOf(s, e - 1))) {
                                        val a = maxOf(s, l.getLineStart(line)); val b = minOf(e, l.getLineEnd(line))
                                        squiggle(col, l.getHorizontalPosition(a, true), l.getHorizontalPosition(b, true), l.getLineBottom(line) - 3.dp.toPx(), 3.dp.toPx(), 1.6.dp.toPx())
                                    }
                                }
                            }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val mod = e.isMetaPressed || e.isCtrlPressed
                                val open = completion
                                if (open != null) {
                                    when (e.key) {
                                        Key.DirectionDown -> { completion = open.move(1); return@onPreviewKeyEvent true }
                                        Key.DirectionUp -> { completion = open.move(-1); return@onPreviewKeyEvent true }
                                        Key.Enter, Key.NumPadEnter, Key.Tab -> if (!mod) { accept(open.items[open.index]); return@onPreviewKeyEvent true }
                                        Key.Escape -> { completion = null; wanted = false; return@onPreviewKeyEvent true }
                                        else -> {}
                                    }
                                }
                                when {
                                    e.key == Key.Spacebar && e.isCtrlPressed -> { wanted = true; openCompletion(value); true }
                                    e.key == Key.Enter && mod -> { if (e.isShiftPressed) onRunAll() else onRunCurrent(); true }
                                    e.key == Key.Enter && !e.isShiftPressed && !e.isAltPressed -> { onValueChange(EditorActions.newline(value)); completion = null; true }
                                    e.key == Key.Tab && !mod -> { onValueChange(if (e.isShiftPressed) EditorActions.outdent(value) else EditorActions.indent(value)); true }
                                    e.key == Key.Backspace && !mod && !e.isAltPressed -> EditorActions.backspace(value)?.let { onValueChange(it); true } ?: false
                                    mod && e.key == Key.Slash -> { edit(EditorActions.toggleComment(value)); true }
                                    mod && e.key == Key.D && !e.isAltPressed -> { edit(EditorActions.duplicate(value)); true }
                                    mod && e.key == Key.W -> { onValueChange(EditorActions.extendSelection(value)); true }
                                    mod && e.isAltPressed && e.key == Key.L -> { support.onFormat(); true }
                                    mod && e.isAltPressed && e.key == Key.B -> { support.onShowDdl(value.selection.end); true }
                                    mod && e.key == Key.B -> { support.onGoto(value.selection.end); true }
                                    mod && !e.isShiftPressed && e.key == Key.Z -> support.onUndo()
                                    else -> false
                                }
                            },
                        textStyle = style,
                        cursorBrush = SolidColor(c.text),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                    )
                    completion?.let { st ->
                        val l = layout
                        if (l != null) {
                            val r = l.getCursorRect(value.selection.end.coerceIn(0, l.layoutInput.text.length))
                            CompletionPopup(st, IntOffset(with(density) { 3.dp.roundToPx() } + (r.left - (st.ctx.prefix.length * charW)).toInt().coerceAtLeast(0), r.bottom.toInt() + 2), ::accept)
                        }
                    }
                }
            }
        }
    }
}
