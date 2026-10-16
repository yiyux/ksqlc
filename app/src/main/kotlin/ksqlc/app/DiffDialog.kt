package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.Namespace
import ksqlc.drivers.ObjectDiff
import ksqlc.drivers.ObjectStatus
import ksqlc.sql.DiffKind
import ksqlc.sql.DiffLine
import ksqlc.sql.diffLines

private class NumberedLine(val leftNo: Int?, val rightNo: Int?, val line: DiffLine)

private fun numbered(d: ObjectDiff): List<NumberedLine> {
    var l = 0; var r = 0
    return diffLines(d.left?.lines().orEmpty(), d.right?.lines().orEmpty()).map { row ->
        NumberedLine(if (row.left != null) ++l else null, if (row.right != null) ++r else null, row)
    }
}

@Composable
internal fun <T> Chooser(text: String, options: List<T>, label: (T) -> String, tag: String, width: androidx.compose.ui.unit.Dp, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(width)) {
        DropdownField(text, { open = true }, Modifier.fillMaxWidth().testTag(tag))
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(label(o), fontSize = 13.sp) }, modifier = Modifier.testTag("$tag:$i"), onClick = { open = false; onPick(i) }) }
        }
    }
}

/** Compares a schema of the active connection with one of any saved connection (tags `diff-dialog`, `diff-run`, `diff-item:<n>`, `diff-line:<n>`, `diff-hide-same`). */
@Composable
fun DiffDialog(state: AppState) {
    val job = state.diffJob ?: return
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    ModalOverlay(state::closeDiff, Modifier.testTag("diff-dialog"), width = 1180.dp, height = 760.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("Comparar esquemas") { ToolButton(IconKind.Close, "Cerrar", state::closeDiff) }
            Separator()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.leftName, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Chooser(job.leftNamespaces.getOrNull(job.leftIndex)?.label ?: "—", job.leftNamespaces, Namespace::label, "diff-left-ns", 170.dp) { job.leftIndex = it }
                Text("↔", fontSize = 14.sp, color = c.textMuted)
                Chooser(job.sources.getOrNull(job.rightIndexSource)?.name ?: "—", job.sources, { it.name }, "diff-right-source", 190.dp, job::chooseSource)
                Chooser(job.rightNamespaces.getOrNull(job.rightIndex)?.label ?: "—", job.rightNamespaces, Namespace::label, "diff-right-ns", 170.dp) { job.rightIndex = it }
                if (job.running) TextAction("Cancelar", job::cancel, Modifier.testTag("diff-cancel")) else TextAction("Comparar", job::compare, Modifier.testTag("diff-run"), primary = true)
                if (job.running) Text(job.progress, fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.testTag("diff-progress"))
                Spacer(Modifier.weight(1f))
                KCheckbox(job.hideSame, { job.hideSame = it }, label = "Ocultar iguales", modifier = Modifier.testTag("diff-hide-same"))
            }
            job.error?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("diff-error"), fontSize = 12.sp, color = c.error) }
            Separator()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val all = job.results
                val shown = remember(all, job.hideSame) { all?.filter { !job.hideSame || it.status != ObjectStatus.SAME }.orEmpty() }
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    if (all == null) Text("Elige los dos lados y pulsa Comparar.", Modifier.padding(12.dp), fontSize = 12.sp, color = c.textMuted)
                    else if (shown.isEmpty()) Text(if (all.isEmpty()) "No hay objetos que comparar." else "No hay diferencias.", Modifier.padding(12.dp).testTag("diff-none"), fontSize = 12.sp, color = c.textMuted)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown.size) { i ->
                            val d = shown[i]
                            val (mark, color) = when (d.status) {
                                ObjectStatus.ONLY_LEFT -> "−" to c.error; ObjectStatus.ONLY_RIGHT -> "+" to c.success
                                ObjectStatus.DIFFERENT -> "≠" to c.warning; ObjectStatus.SAME -> "=" to c.textMuted
                            }
                            Row(
                                Modifier.testTag("diff-item:$i").fillMaxWidth().height(26.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (d == job.selected) c.selectionInactive else Color.Transparent).clickable { job.selected = d },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(mark, Modifier.padding(start = 8.dp).width(14.dp), fontSize = 13.sp, color = color)
                                Text(d.name, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(d.kind.name.lowercase().replace('_', ' '), fontSize = 11.sp, color = c.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
                Separator(vertical = true)
                val sel = job.selected
                if (sel == null) Box(Modifier.weight(1f).fillMaxHeight().background(c.editorBg)) {}
                else {
                    val rows = remember(sel) { numbered(sel) }
                    val list = rememberLazyListState()
                    Column(Modifier.weight(1f).fillMaxHeight().background(c.editorBg)) {
                        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (sel.left == null) "— (no existe)" else job.leftName, Modifier.weight(1f).padding(start = 40.dp), fontSize = 12.sp, color = c.textSecondary, maxLines = 1)
                            Text(if (sel.right == null) "— (no existe)" else job.sources.getOrNull(job.rightIndexSource)?.name.orEmpty(), Modifier.weight(1f).padding(start = 40.dp), fontSize = 12.sp, color = c.textSecondary, maxLines = 1)
                        }
                        Separator()
                        LazyColumn(Modifier.fillMaxSize(), state = list) {
                            items(rows.size) { i ->
                                val n = rows[i]
                                val (lbg, rbg) = when (n.line.kind) {
                                    DiffKind.SAME -> Color.Transparent to Color.Transparent
                                    DiffKind.REMOVED -> c.diffRemoved to Color.Transparent
                                    DiffKind.ADDED -> Color.Transparent to c.diffAdded
                                    DiffKind.CHANGED -> c.diffChanged to c.diffChanged
                                }
                                Row(Modifier.testTag("diff-line:$i").fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 18.dp)) {
                                    Text(n.leftNo?.toString().orEmpty(), Modifier.width(36.dp).fillMaxHeight().background(lbg).padding(end = 6.dp), style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 18.sp), color = c.rowNumber, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                    Text(n.line.left.orEmpty(), Modifier.weight(1f).fillMaxHeight().background(lbg).padding(horizontal = 4.dp), style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.gridText)
                                    Text(n.rightNo?.toString().orEmpty(), Modifier.width(36.dp).fillMaxHeight().background(rbg).padding(end = 6.dp), style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 18.sp), color = c.rowNumber, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                    Text(n.line.right.orEmpty(), Modifier.weight(1f).fillMaxHeight().background(rbg).padding(horizontal = 4.dp), style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.gridText)
                                }
                            }
                        }
                    }
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sel = job.selected
                TextAction("Copiar DDL izquierdo", { sel?.left?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = sel?.left != null)
                TextAction("Copiar DDL derecho", { sel?.right?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = sel?.right != null)
                Spacer(Modifier.weight(1f))
                TextAction("Cerrar", state::closeDiff, primary = true)
            }
        }
    }
}
