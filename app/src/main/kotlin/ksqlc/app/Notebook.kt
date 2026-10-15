package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*

private const val GRID_ROWS_SHOWN = 8

/**
 * The console as a notebook: one cell per statement of the editor text, each with its own run button and its result right
 * under it. ⇧↵ or ⌘↵ in a cell runs it. Tags `notebook`, `nb-cell:i`, `nb-text:i`, `nb-run:i`, `nb-delete:i`, `nb-result:i`, `nb-add`.
 */
@Composable
fun NotebookView(state: AppState, modifier: Modifier = Modifier) {
    val cells = state.cells()
    val text = state.editor.text
    val list = rememberLazyListState()
    LazyColumn(modifier.testTag("notebook"), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(cells) { i, r -> NotebookCell(state, i, r.text(text)) }
        item {
            Box(Modifier.testTag("nb-add")) { TextAction("+ Celda", state::addCell, enabled = !state.running) }
        }
    }
}

@Composable
private fun NotebookCell(state: AppState, i: Int, source: String) {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(8.dp)
    val run = state.cellRuns[i]
    val selected = state.notebookCell == i
    // The field keeps its own text and caret. Statements are trimmed when the text is split, so spaces and line breaks at the ends
    // exist only here: it takes the text from outside only when more than that changed (another edit, a deleted cell).
    var value by remember { mutableStateOf(TextFieldValue(source, TextRange(source.length))) }
    if (value.text.trim() != source) value = TextFieldValue(source, TextRange(source.length.coerceAtMost(value.selection.end)))
    val transformation = remember(c) { VisualTransformation { t -> TransformedText(highlight(t.text, c), OffsetMapping.Identity) } }
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, if (selected) c.accent else c.border, shape).background(c.editorBg).testTag("nb-cell:$i"),
    ) {
        Row(Modifier.fillMaxWidth().height(30.dp).background(c.toolbarBg).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Play, "Ejecutar celda (⇧↵)", { state.notebookCell = i; state.runCell(i) }, Modifier.testTag("nb-run:$i"), enabled = !state.running, tint = c.success)
            Text("[${i + 1}]", fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
            when {
                run == null -> {}
                run.running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.accentIcon)
                else -> Text(
                    (if (run.ok) "${run.millis} ms" else "error") + if (run.source != source) " · desactualizado" else "",
                    fontSize = 12.sp, color = if (run.ok) c.textMuted else c.error,
                )
            }
            Spacer(Modifier.weight(1f))
            ToolButton(IconKind.Close, "Eliminar celda", { state.deleteCell(i) }, Modifier.testTag("nb-delete:$i"), enabled = !state.running)
        }
        BasicTextField(
            value, { value = it; if (it.text.trim() != source) state.editCell(i, it.text.trim()) },
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).testTag("nb-text:$i")
                .onFocusChanged { if (it.isFocused) state.notebookCell = i }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isShiftPressed || e.isMetaPressed)) { state.notebookCell = i; state.runCell(i); true } else false
                },
            textStyle = MonoStyle.copy(color = c.synIdent), cursorBrush = SolidColor(c.text), visualTransformation = transformation,
        )
        val tab = run?.tab
        when {
            tab != null -> {
                Separator()
                // a few rows are shown at their natural height; more scroll inside the grid
                val height = (HEADER_DP + ROW_DP * tab.rows.size.coerceIn(1, GRID_ROWS_SHOWN) + 8).dp
                Grid(tab, onLoadMore = {}, modifier = Modifier.fillMaxWidth().height(height).testTag("nb-result:$i"), showRowsPill = false)
                Text(
                    if (tab.exhausted) "${tab.rows.size} filas" else "Primeras ${tab.rows.size} filas",
                    fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            run != null && !run.running && run.message != null -> {
                Separator()
                Text(run.message, fontSize = 13.sp, color = if (run.ok) c.textSecondary else c.error, modifier = Modifier.padding(10.dp).testTag("nb-result:$i"))
            }
        }
    }
}

private const val HEADER_DP = 30
private const val ROW_DP = 27
