package ksqlc.app

import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*

private val RowNumberWidth = 39.dp
private val ColumnWidth = 112.dp
private val HeaderHeight = 26.dp
private val RowHeight = 27.dp

/** Display text of a cell: SQL NULL as `<null>`, line breaks as ⏎ so every row stays one line high. */
fun cellText(v: Any?): String = v?.toString()?.replace("\r\n", "⏎")?.replace('\n', '⏎')?.replace('\r', '⏎') ?: "<null>"

/**
 * Result table: row-number column, 26dp header, 27dp rows with 1dp grid lines, 112dp columns, numbers right-aligned.
 * Click selects a cell (⌘C / Ctrl+C copies its value); the "more rows" pill floats at the bottom.
 */
@Composable
fun Grid(tab: ResultTab, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    val hscroll = rememberScrollState()
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var selected by remember(tab) { mutableStateOf<Pair<Int, Int>?>(null) }
    val cols = tab.result.columns

    Box(
        modifier.background(c.editorBg).focusRequester(focus).focusTarget().testTag("grid").onPreviewKeyEvent { e ->
            val sel = selected
            if (e.type == KeyEventType.KeyDown && (e.isMetaPressed || e.isCtrlPressed) && e.key == Key.C && sel != null) {
                tab.rows.getOrNull(sel.first)?.getOrNull(sel.second)?.let { clipboard.setText(AnnotatedString(it.toString())) }
                true
            } else false
        },
    ) {
        Column(Modifier.fillMaxSize().horizontalScroll(hscroll)) {
            Row(Modifier.height(HeaderHeight).background(c.headerBg)) {
                Box(Modifier.width(RowNumberWidth).fillMaxHeight().drawBehind { drawLine(c.windowBg, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) })
                cols.forEach { col ->
                    Row(
                        Modifier.width(ColumnWidth).fillMaxHeight().drawBehind { drawLine(c.windowBg, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KIcon(IconKind.Column, tint = c.accentIcon, size = 14.dp)
                        Text(col.name, fontSize = 13.sp, color = c.gridText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.weight(1f).width(RowNumberWidth + ColumnWidth * cols.size)) {
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    itemsIndexed(tab.rows) { i, row ->
                        val rowSelected = selected?.first == i
                        Row(Modifier.height(RowHeight)) {
                            Box(
                                Modifier.width(RowNumberWidth).fillMaxHeight().background(if (rowSelected) c.currentLine else androidx.compose.ui.graphics.Color.Transparent)
                                    .drawBehind { lines(c.gridLine) }.padding(end = 6.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Text("${i + 1}", fontSize = 12.sp, style = MonoStyle.copy(fontSize = 12.sp), color = if (rowSelected) c.gutterTextCurrent else c.rowNumber) }
                            row.forEachIndexed { j, v ->
                                val isSel = selected == i to j
                                Box(
                                    Modifier.width(ColumnWidth).fillMaxHeight()
                                        .background(if (isSel) c.selection else androidx.compose.ui.graphics.Color.Transparent)
                                        .drawBehind { lines(c.gridLine) }
                                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { selected = i to j; focus.requestFocus() }
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = if (v is Number) Alignment.CenterEnd else Alignment.CenterStart,
                                ) {
                                    Text(
                                        cellText(v), maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                                        style = MonoStyle, color = if (v == null) c.nullText else c.gridText,
                                        textAlign = if (v is Number) TextAlign.End else TextAlign.Start,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ListScrollbar(list, Modifier.align(Alignment.CenterEnd).padding(top = HeaderHeight).fillMaxHeight())
        val wide = hscroll.maxValue > 0
        if (wide) HorizontalScrollbar(rememberScrollbarAdapter(hscroll), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp).testTag("grid-hscroll"), style = scrollbarStyle())
        RowsPill(tab, onLoadMore, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (wide) 18.dp else 10.dp))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.lines(color: androidx.compose.ui.graphics.Color) {
    drawLine(color, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

/** Floating "N filas" pill with a load-more button while the cursor has rows left. */
@Composable
private fun RowsPill(tab: ResultTab, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(32.dp).clip(shape).background(c.windowBg, shape).border(1.dp, c.border, shape).padding(horizontal = 10.dp).testTag("rows-pill").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${tab.rows.size} filas${if (tab.exhausted) "" else "+"}", fontSize = 12.sp, color = c.text)
        if (!tab.exhausted && !tab.result.truncated) {
            VSeparator()
            if (tab.loading) Text("Cargando…", fontSize = 12.sp, color = c.textSecondary)
            else ToolButton(IconKind.Plus, "Cargar más", onLoadMore)
        }
        if (tab.result.truncated) { VSeparator(); Text("recortado", fontSize = 12.sp, color = c.textSecondary, fontStyle = FontStyle.Italic) }
    }
}
