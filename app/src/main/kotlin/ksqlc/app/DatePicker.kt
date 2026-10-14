package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ksqlc.app.ui.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val Spanish = Locale("es")
private val WEEKDAYS = listOf("L", "M", "X", "J", "V", "S", "D")

/** Under the cell when it fits, above it otherwise; always inside the window. */
private object BelowOrAbove : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** A tap target that never takes the keyboard from the cell editor (it is not focusable, unlike `clickable`). */
@Composable
private fun Tap(tag: String, modifier: Modifier = Modifier, onTap: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val current by rememberUpdatedState(onTap)
    Box(modifier.testTag(tag).pointerInput(Unit) { detectTapGestures { current() } }, contentAlignment = Alignment.Center, content = content)
}

/**
 * Calendar and clock for a date-like cell being edited. [text] is the cell editor's text, the single source of truth:
 * every pick rewrites it (in the shape the column's type needs) through [onText], and typing in the cell moves the picker.
 * [onText]'s second argument asks to finish the edit (a date is done once a day is picked). Not focusable.
 */
@Composable
fun DatePickerPopup(kind: TemporalKind, text: String, onText: (String, Boolean) -> Unit, onNull: () -> Unit, onAccept: () -> Unit) {
    val c = Ksqlc.colors
    val parsed = parseMoment(text, kind)
    val current = parsed ?: remember { now() }
    var month by remember { mutableStateOf(YearMonth.from(current.date)) }
    LaunchedEffect(parsed?.date) { parsed?.date?.let { month = YearMonth.from(it) } }
    val today = remember { LocalDate.now() }
    fun put(m: Moment, done: Boolean = false) = onText(formatMoment(m, kind), done)
    val shape = RoundedCornerShape(8.dp)

    Popup(popupPositionProvider = BelowOrAbove, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.width(250.dp).clip(shape).background(c.popupBg, shape).border(1.dp, c.border, shape).padding(8.dp).testTag("date-picker"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (kind.hasDate) {
                Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tap("month-prev", Modifier.size(26.dp), { month = month.minusMonths(1) }) { KIcon(IconKind.ChevronRight, Modifier.rotate(180f), tint = c.textSecondary, size = 14.dp) }
                    Text(
                        month.month.getDisplayName(TextStyle.FULL, Spanish).replaceFirstChar { it.uppercase() } + " " + month.year,
                        Modifier.weight(1f).testTag("month-title"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center,
                    )
                    Tap("month-next", Modifier.size(26.dp), { month = month.plusMonths(1) }) { KIcon(IconKind.ChevronRight, tint = c.textSecondary, size = 14.dp) }
                }
                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = c.textMuted, textAlign = TextAlign.Center) }
                }
                monthPage(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().height(28.dp)) {
                        week.forEach { d ->
                            val selected = parsed?.date == d
                            val inMonth = YearMonth.from(d) == month
                            Tap("day:$d", Modifier.weight(1f).fillMaxHeight().padding(1.dp), { put(current.copy(date = d), done = kind == TemporalKind.DATE) }) {
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp))
                                        .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                                        .then(if (d == today && !selected) Modifier.border(1.dp, c.accent, RoundedCornerShape(5.dp)) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) { Text(d.dayOfMonth.toString(), fontSize = 12.sp, color = if (selected) c.onAccent else if (inMonth) c.text else c.textMuted) }
                            }
                        }
                    }
                }
            }
            if (kind.hasTime) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun unit(tag: String, value: Int, step: (Int) -> Moment) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Tap("$tag+", Modifier.size(width = 40.dp, height = 20.dp), { put(step(1)) }) { KIcon(IconKind.ChevronDown, Modifier.rotate(180f), tint = c.textSecondary, size = 12.dp) }
                            Text("%02d".format(value), Modifier.testTag("$tag-value"), style = MonoStyle.copy(fontSize = 15.sp), color = c.text)
                            Tap("$tag-", Modifier.size(width = 40.dp, height = 20.dp), { put(step(-1)) }) { KIcon(IconKind.ChevronDown, tint = c.textSecondary, size = 12.dp) }
                        }
                    }
                    unit("h", current.time.hour) { current.withTime(h = current.time.hour + it) }
                    Text(":", color = c.textSecondary, modifier = Modifier.padding(horizontal = 2.dp))
                    unit("m", current.time.minute) { current.withTime(m = current.time.minute + it) }
                    Text(":", color = c.textSecondary, modifier = Modifier.padding(horizontal = 2.dp))
                    unit("s", current.time.second) { current.withTime(s = current.time.second + it) }
                }
                if (kind.hasOffset) Text("UTC" + current.offset.id.let { if (it == "Z") "+00:00" else it }, Modifier.fillMaxWidth().testTag("offset"), fontSize = 11.sp, color = c.textMuted, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tap("date-now", Modifier.height(26.dp), { put(now().let { n -> if (kind == TemporalKind.DATETIME_TZ) n else n }, done = kind == TemporalKind.DATE) }) {
                    Text(if (kind == TemporalKind.DATE) "Hoy" else "Ahora", fontSize = 12.sp, color = c.link)
                }
                Tap("date-null", Modifier.height(26.dp), onNull) { Text("NULL", fontSize = 12.sp, color = c.link) }
                Spacer(Modifier.weight(1f))
                if (kind != TemporalKind.DATE) Tap("date-ok", Modifier.height(24.dp).clip(RoundedCornerShape(5.dp)).background(c.accent).padding(horizontal = 12.dp), onAccept) { Text("Aceptar", fontSize = 12.sp, color = c.onAccent) }
            }
        }
    }
}
