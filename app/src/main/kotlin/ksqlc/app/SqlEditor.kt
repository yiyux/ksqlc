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
import ksqlc.sql.TokenKind
import ksqlc.sql.tokenize

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

/**
 * SQL console editor drawn in the style of an IDE: line-number gutter with its own border, highlighted
 * current line, a guide at column 120, syntax colours, and an outline plus timing around the statement that last ran.
 * Lines never wrap; the editor scrolls horizontally instead, so a line number always matches one text line.
 */
@Composable
fun SqlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    marker: RunMarker?,
    onRunCurrent: () -> Unit,
    onRunAll: () -> Unit,
    onCaret: (line: Int, column: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ksqlc.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val lineH = with(density) { EditorLineHeight.toPx() }
    val charW = remember(measurer) { measurer.measure("0", EditorStyle).size.width.toFloat() }
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val caretLine = lineOf(value.text, value.selection.end)
    val style = EditorStyle.copy(color = c.synIdent)
    val numberStyle = EditorStyle.copy(fontSize = 12.sp)

    val transformation = remember(c) {
        VisualTransformation { text -> TransformedText(highlight(text.text, c), OffsetMapping.Identity) }
    }

    LaunchedEffect(value.text, value.selection) { onCaret(caretLine + 1, columnOf(value.text, value.selection.end)) }

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
                        value, onValueChange,
                        Modifier.padding(start = 3.dp, end = 48.dp)
                            // the field fills the viewport, so a click anywhere in the editor focuses it
                            .heightIn(min = with(density) { viewport.height.toDp() })
                            .widthIn(min = (viewportDp - EditorGutterWidth - 51.dp).coerceAtLeast(0.dp))
                            .drawBehind {
                                val m = marker; val l = layout
                                if (m != null && l != null && m.end <= l.layoutInput.text.length && m.end > m.start) {
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
                            }
                            .onPreviewKeyEvent { e ->
                                val mod = e.isMetaPressed || e.isCtrlPressed
                                when {
                                    e.type == KeyEventType.KeyDown && e.key == Key.Enter && mod -> { if (e.isShiftPressed) onRunAll() else onRunCurrent(); true }
                                    e.type == KeyEventType.KeyDown && e.key == Key.Tab && !mod && !e.isShiftPressed -> {
                                        val s = value.selection
                                        onValueChange(TextFieldValue(value.text.replaceRange(s.min, s.max, "    "), TextRange(s.min + 4)))
                                        true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = style,
                        cursorBrush = SolidColor(c.text),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                    )
                }
            }
        }
    }
}
