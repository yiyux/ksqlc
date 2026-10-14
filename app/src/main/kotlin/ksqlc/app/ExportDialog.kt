package ksqlc.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.drivers.ExportFormat

/** Format and destination of an export (tags `export-dialog`, `export-path`, `export-run`, `export-clipboard`, `export-format:<n>`). */
@Composable
fun ExportDialog(state: AppState) {
    val job = state.exportJob ?: return
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    val csvLike = job.format == ExportFormat.CSV || job.format == ExportFormat.TSV
    ModalOverlay(state::closeExport, Modifier.testTag("export-dialog"), width = 600.dp, height = 330.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("Exportar — ${job.request.name}") { ToolButton(IconKind.Close, "Cerrar", state::closeExport) }
            Separator()
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Formato:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    SegmentedControl(ExportFormat.entries.map { it.label }, job.format.ordinal, { job.format = ExportFormat.entries[it] }, tag = "export-format")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Destino:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    SegmentedControl(listOf("Archivo", "Portapapeles"), if (job.toClipboard) 1 else 0, { job.toClipboard = it == 1 }, tag = "export-dest")
                }
                if (!job.toClipboard) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Archivo:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    CompactTextField(job.path, { job.path = it }, Modifier.weight(1f), placeholder = "${job.request.name}.${job.format.extension}", testTag = "export-path", height = 28.dp)
                    TextAction("…", { chooseFile(true, "Exportar", "${job.request.name}.${job.format.extension}")?.let { job.path = it } }, Modifier.testTag("export-browse"))
                }
                if (csvLike) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.width(90.dp))
                    KCheckbox(job.header, { job.header = it }, label = "Incluir cabecera", modifier = Modifier.testTag("export-header"))
                }
                job.request.note?.let { Text(it, fontSize = 12.sp, color = c.textSecondary) }
                if (job.toClipboard) Text("Al portapapeles van como máximo $CLIPBOARD_ROWS filas.", fontSize = 12.sp, color = c.textSecondary)
                if (job.running) Text("${job.progress} filas…", Modifier.testTag("export-progress"), fontSize = 12.sp, color = c.textSecondary)
                job.error?.let { Text(it, Modifier.testTag("export-error"), fontSize = 12.sp, color = c.error) }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (job.running) TextAction("Cancelar exportación", job::cancel, Modifier.testTag("export-cancel"))
                else TextAction("Cerrar", state::closeExport)
                TextAction("Exportar", { job.start({ clipboard.setText(AnnotatedString(it)) }, state::closeExport) }, Modifier.testTag("export-run"), enabled = !job.running, primary = true)
            }
        }
    }
}
