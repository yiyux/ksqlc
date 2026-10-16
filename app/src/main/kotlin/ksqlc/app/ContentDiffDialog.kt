package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.Namespace
import ksqlc.drivers.RowStatus

private fun cell(v: Any?) = when (v) { null -> "NULL"; is ByteArray -> "<${v.size} bytes>"; else -> v.toString() }

/** Compares the rows of a table of the active connection with a table of any saved connection (tags `content-diff-dialog`, `content-diff-run`, `content-diff-row:<n>`). */
@Composable
fun ContentDiffDialog(state: AppState) {
    val job = state.contentDiffJob ?: return
    val c = Ksqlc.colors
    ModalOverlay(state::closeContentDiff, Modifier.testTag("content-diff-dialog"), width = 1180.dp, height = 760.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("Comparar contenido") { ToolButton(IconKind.Close, "Cerrar", state::closeContentDiff) }
            Separator()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${job.leftName} · ${job.leftObj.ns.label}.${job.leftObj.name}", fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                Text("↔", fontSize = 14.sp, color = c.textMuted)
                Chooser(job.sources.getOrNull(job.rightIndexSource)?.name ?: "—", job.sources, { it.name }, "content-diff-right-source", 170.dp, job::chooseSource)
                Chooser(job.rightNamespaces.getOrNull(job.rightIndex)?.label ?: "—", job.rightNamespaces, Namespace::label, "content-diff-right-ns", 150.dp, job::chooseNamespace)
                Chooser(job.rightTables.getOrNull(job.rightTableIndex)?.name ?: "—", job.rightTables, { it.name }, "content-diff-right-table", 170.dp) { job.rightTableIndex = it }
                if (job.running) TextAction("Cancelar", job::cancel, Modifier.testTag("content-diff-cancel")) else TextAction("Comparar", job::compare, Modifier.testTag("content-diff-run"), primary = true)
            }
            job.error?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("content-diff-error"), fontSize = 12.sp, color = c.error) }
            Separator()
            val r = job.result
            if (r == null) Text(if (job.running) "Leyendo las filas…" else "Elige la tabla de la derecha y pulsa Comparar.", Modifier.padding(12.dp), fontSize = 12.sp, color = c.textMuted)
            else {
                val count = { s: RowStatus -> r.rows.count { it.status == s } }
                Text(
                    "Clave: ${r.key.joinToString()} · iguales ${r.same} · distintas ${count(RowStatus.DIFFERENT)} · solo a la izquierda ${count(RowStatus.ONLY_LEFT)} · solo a la derecha ${count(RowStatus.ONLY_RIGHT)}",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("content-diff-summary"), fontSize = 12.sp, color = c.textSecondary,
                )
                if (r.onlyLeftColumns.isNotEmpty() || r.onlyRightColumns.isNotEmpty())
                    Text("Columnas que no se comparan — solo a la izquierda: ${r.onlyLeftColumns.joinToString().ifEmpty { "—" }}; solo a la derecha: ${r.onlyRightColumns.joinToString().ifEmpty { "—" }}", Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp, color = c.textMuted)
                if (r.truncated) Text("Alguna tabla tiene más filas de las que se leen: las que faltan a un lado pueden ser filas no leídas.", Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("content-diff-truncated"), fontSize = 12.sp, color = c.warning)
                Separator()
                if (r.rows.isEmpty()) Text("No hay diferencias.", Modifier.padding(12.dp).testTag("content-diff-none"), fontSize = 12.sp, color = c.textMuted)
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f).fillMaxWidth().horizontalScroll(scroll)) {
                    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("", Modifier.width(28.dp))
                        r.columns.forEach { Text(it, Modifier.width(150.dp).padding(horizontal = 4.dp), fontSize = 12.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Separator()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(r.rows.size) { i ->
                            val d = r.rows[i]
                            val (mark, color) = when (d.status) {
                                RowStatus.ONLY_LEFT -> "−" to c.error; RowStatus.ONLY_RIGHT -> "+" to c.success; RowStatus.DIFFERENT -> "≠" to c.warning
                            }
                            Row(Modifier.testTag("content-diff-row:$i").height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mark, Modifier.width(28.dp).padding(start = 8.dp), fontSize = 13.sp, color = color)
                                r.columns.indices.forEach { j ->
                                    val (text, bg) = when (d.status) {
                                        RowStatus.ONLY_LEFT -> cell(d.left!![j]) to c.diffRemoved
                                        RowStatus.ONLY_RIGHT -> cell(d.right!![j]) to c.diffAdded
                                        RowStatus.DIFFERENT -> if (j in d.changed) "${cell(d.left!![j])} → ${cell(d.right!![j])}" to c.diffChanged else cell(d.left!![j]) to Color.Transparent
                                    }
                                    Text(text, Modifier.width(150.dp).fillMaxHeight().background(bg).padding(horizontal = 4.dp), style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 24.sp), color = c.gridText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (r == null) Spacer(Modifier.weight(1f))
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextAction("Cerrar", state::closeContentDiff, primary = true)
            }
        }
    }
}
