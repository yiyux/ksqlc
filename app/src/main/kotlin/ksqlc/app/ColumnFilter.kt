package ksqlc.app

import ksqlc.core.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.border
import ksqlc.app.ui.*

/** Under the anchor, kept inside the window (moved left, or up, when it would stick out). */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        (anchorBounds.bottom + 4).coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused = src.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.height(30.dp).clip(shape).background(c.editorBg).border(1.dp, if (focused) c.accent else c.border, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KIcon(IconKind.Search, tint = c.textSecondary, size = 14.dp)
        BasicTextField(value, onChange, Modifier.weight(1f).testTag("colfilter-search"), singleLine = true, interactionSource = src, textStyle = UiStyle.copy(color = c.text), cursorBrush = SolidColor(c.text))
    }
}

/**
 * "Local filter" card for column [j] of [tab]: search box, every distinct value of the loaded rows with its count, and a
 * checkbox each. Checking values keeps only the rows that have one of them; with nothing checked there is no filter.
 * Tags: `colfilter`, `colfilter-search`, `colfilter-all`, `colfilter-item:<n>` (n = position in the list shown).
 */
@Composable
fun ColumnFilterPopup(tab: ResultTab, j: Int, onDismiss: () -> Unit) {
    val c = Ksqlc.colors
    val name = tab.result.columns[j].name
    var query by remember { mutableStateOf("") }
    val values = remember(tab.rows.size) { tab.distinct(j) }
    val shown = remember(values, query) { values.filter { query.isBlank() || (it.first ?: "<null>").contains(query.trim(), ignoreCase = true) } }
    val selected = tab.filters[j].orEmpty()
    fun set(next: Set<String?>) { if (next.isEmpty()) tab.filters.remove(j) else tab.filters[j] = next }
    val allShownSelected = shown.isNotEmpty() && shown.all { it.first in selected }

    Popup(popupPositionProvider = BelowAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.testTag("colfilter").width(250.dp).height(340.dp).clip(shape).background(c.popupBg, shape).border(1.dp, c.border, shape).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("Filtro local de '%s'", name), Modifier.fillMaxWidth(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SearchField(query, { query = it }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KCheckbox(allShownSelected, { if (allShownSelected) set(selected - shown.map { it.first }.toSet()) else set(selected + shown.map { it.first }) }, Modifier.testTag("colfilter-all"), enabled = shown.isNotEmpty())
                Text(tr("Valor"), Modifier.weight(1f), fontSize = 12.sp, color = c.textSecondary)
                Text(tr("Cantidad"), Modifier.width(64.dp), fontSize = 12.sp, color = c.textSecondary)
            }
            Separator()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown.size) { k ->
                    val (value, count) = shown[k]
                    val on = value in selected
                    Row(
                        Modifier.testTag("colfilter-item:$k").fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp)).clickable { set(if (on) selected - value else selected + value) }.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KCheckbox(on, { set(if (on) selected - value else selected + value) })
                        Text(value ?: "<null>", Modifier.weight(1f), fontSize = 13.sp, color = if (value == null) c.nullText else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$count", Modifier.width(64.dp), fontSize = 13.sp, color = c.text)
                    }
                }
            }
            Text(tr("Marca una casilla para filtrar las filas"), Modifier.fillMaxWidth(), fontSize = 12.sp, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}
