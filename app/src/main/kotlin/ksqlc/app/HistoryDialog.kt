package ksqlc.app

import ksqlc.core.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.storage.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault())

/** Statements run on the active connection, newest first, with a search box (tags `history-dialog`, `history-search`, `history:<n>`, `history-insert`). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryDialog(state: AppState) {
    val all = state.historyEntries ?: return
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    val shown = remember(all, query) { all.filter { query.isBlank() || it.sql.contains(query.trim(), ignoreCase = true) } }
    val current = selected?.takeIf { it in shown } ?: shown.firstOrNull()
    fun insert(e: HistoryEntry) { state.appendToEditor(e.sql); state.activeEditor = CONSOLE_TAB; state.closeHistory() }

    ModalOverlay(state::closeHistory, Modifier.testTag("history-dialog"), width = 980.dp, height = 600.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(tr("Historial — %s", state.form.name)) { ToolButton(IconKind.Close, tr("Cerrar"), state::closeHistory) }
            Separator()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(380.dp).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().padding(8.dp)) {
                        CompactTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = tr("Buscar…"), testTag = "history-search")
                    }
                    Separator()
                    if (shown.isEmpty()) Text(if (all.isEmpty()) tr("Aún no se ha ejecutado nada.") else tr("Sin coincidencias."), Modifier.padding(12.dp), fontSize = 12.sp, color = c.textMuted)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(shown.size) { i ->
                            val e = shown[i]
                            Row(
                                Modifier.testTag("history:$i").fillMaxWidth().height(26.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (e == current) c.selectionInactive else Color.Transparent)
                                    .clickable { selected = e },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stamp.format(Instant.ofEpochMilli(e.time)), Modifier.padding(start = 6.dp), fontSize = 11.sp, color = c.textMuted, maxLines = 1)
                                Text(e.sql.trim().lineSequence().first(), fontSize = 12.sp, color = if (e.ok) c.text else c.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(c.editorBg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer { Text(current?.sql.orEmpty(), style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.gridText) }
                    }
                    if (current != null) Text(
                        (if (current.ok) "OK" else tr("Con error")) + " · ${current.millis} ms" + (current.rows?.let { tr(" · %s filas", it) } ?: ""),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = c.textMuted,
                    )
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(tr("Insertar en consola"), { current?.let(::insert) }, Modifier.testTag("history-insert"), enabled = current != null)
                TextAction(tr("Copiar"), { current?.let { clipboard.setText(AnnotatedString(it.sql)) } }, enabled = current != null)
                TextAction(tr("Vaciar historial"), {
                    state.confirmation = Confirmation(tr("Vaciar historial"), tr("Se borran las %d sentencias guardadas de %s.", all.size, state.form.name), tr("Vaciar")) { state.confirmation = null; state.clearHistory() }
                }, enabled = all.isNotEmpty())
                Spacer(Modifier.weight(1f))
                TextAction(tr("Cerrar"), state::closeHistory, primary = true)
            }
        }
    }
}
