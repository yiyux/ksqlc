package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ksqlc.app.ui.*
import ksqlc.sql.CompletionContext
import ksqlc.sql.Suggestion
import ksqlc.sql.SuggestionKind

/** An open completion list: what is being completed, the candidates and which one is highlighted. */
data class CompletionState(val ctx: CompletionContext, val items: List<Suggestion>, val index: Int = 0) {
    fun move(by: Int) = copy(index = (index + by).mod(items.size))
}

private val RowHeight = 24.dp
private const val VISIBLE_ROWS = 12

private fun iconFor(k: SuggestionKind) = when (k) {
    SuggestionKind.TABLE -> IconKind.Table
    SuggestionKind.VIEW -> IconKind.View
    SuggestionKind.COLUMN -> IconKind.Column
    SuggestionKind.SCHEMA -> IconKind.Schema
    SuggestionKind.KEYWORD -> IconKind.Index
}

/** The text a pick inserts: keywords follow the case being typed, names are quoted only when they need it. */
fun insertionFor(s: Suggestion, ctx: CompletionContext, quote: (String) -> String): String = when (s.kind) {
    SuggestionKind.KEYWORD -> if (ctx.prefix.isNotEmpty() && ctx.prefix == ctx.prefix.lowercase()) s.text.lowercase() else s.text
    else -> if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(s.text) && s.text.uppercase() !in ksqlc.sql.SQL_KEYWORDS) s.text else quote(s.text)
}

/** Floating list under the caret. Not focusable, so the editor keeps the keyboard; the editor drives [state] from its key handler. */
@Composable
fun CompletionPopup(state: CompletionState, offset: IntOffset, onPick: (Suggestion) -> Unit) {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(6.dp)
    val list = rememberLazyListState()
    LaunchedEffect(state.index) {
        val info = list.layoutInfo.visibleItemsInfo
        if (info.isNotEmpty() && (state.index < info.first().index || state.index > info.last().index)) list.scrollToItem(state.index)
    }
    Popup(offset = offset, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier.width(340.dp).height(RowHeight * minOf(state.items.size, VISIBLE_ROWS) + 2.dp).clip(shape)
                .background(c.popupBg, shape).border(1.dp, c.border, shape).testTag("completion"),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(1.dp), state = list) {
                itemsIndexed(state.items) { i, s ->
                    Row(
                        Modifier.fillMaxWidth().height(RowHeight).background(if (i == state.index) c.popupSelection else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(s) }.padding(horizontal = 8.dp).testTag("completion:$i"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KIcon(iconFor(s.kind), tint = if (s.kind == SuggestionKind.KEYWORD) c.textMuted else c.accentIcon, size = 14.dp)
                        Text(s.text, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.weight(1f))
                        s.detail?.let { Text(it, fontSize = 12.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
