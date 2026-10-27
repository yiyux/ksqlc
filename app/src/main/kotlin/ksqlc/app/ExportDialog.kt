package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.drivers.ExportFormat

/**
 * Export dialog: source, extractor (format), options and output file on the left, a live preview on the right
 * (tags `export-dialog`, `export-format`, `export-format:<n>`, `export-header`, `export-transpose`, `export-rowheader`,
 * `export-path`, `export-browse`, `export-preview`, `export-file`, `export-clipboard`, `export-error`).
 */
@Composable
fun ExportDialog(state: AppState) {
    val job = state.exportJob ?: return
    if (!state.exportDialogOpen) return
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    var formats by remember { mutableStateOf(false) }
    val preview = job.previewText()
    ModalOverlay(state::closeExport, Modifier.testTag("export-dialog"), width = 860.dp, height = 590.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("Exportar datos") { ToolButton(IconKind.Close, "Cerrar", state::closeExport) }
            Separator()
            Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldLabel("Origen:")
                    CompactTextField(job.request.source, {}, Modifier.fillMaxWidth(), readOnly = true, testTag = "export-source", height = 30.dp)
                    FieldLabel("Extractor:")
                    Box {
                        DropdownField(job.format.label, { formats = true }, Modifier.fillMaxWidth().testTag("export-format"))
                        DropdownMenu(formats, { formats = false }) {
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text(f.label, fontSize = 13.sp) }, modifier = Modifier.testTag("export-format:${f.ordinal}"), onClick = { formats = false; job.chooseFormat(f) })
                            }
                        }
                    }
                    if (job.request.writeLoaded != null) {
                        FieldLabel("Filas:")
                        KCheckbox(job.allRows, { job.allRows = true }, label = "Todas las filas de la consulta", enabled = !job.running, modifier = Modifier.testTag("export-all"))
                        KCheckbox(!job.allRows, { job.allRows = false }, label = "Solo las ${job.request.loaded} cargadas", enabled = !job.running, modifier = Modifier.testTag("export-loaded"))
                    }
                    KCheckbox(job.transpose, { job.transpose = it }, label = "Transponer", enabled = job.delimited, modifier = Modifier.testTag("export-transpose"))
                    KCheckbox(job.header, { job.header = it }, label = "Añadir cabecera de columnas", enabled = job.delimited, modifier = Modifier.testTag("export-header"))
                    KCheckbox(job.rowHeader, { job.rowHeader = it }, label = "Añadir cabecera de filas", enabled = job.delimited, modifier = Modifier.testTag("export-rowheader"))
                    Spacer(Modifier.height(2.dp))
                    FieldLabel("Archivo de salida:")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompactTextField(job.path, job::editPath, Modifier.weight(1f), testTag = "export-path", height = 30.dp)
                        ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(true, "Exportar", job.path.substringAfterLast('/'))?.let(job::editPath) }, Modifier.testTag("export-browse"))
                    }
                    job.request.note?.let { Text(it, fontSize = 12.sp, color = c.textSecondary) }
                    Text("Al portapapeles van como máximo $CLIPBOARD_ROWS filas.", fontSize = 12.sp, color = c.textSecondary)
                    if (job.running) Text("${job.progress} filas…", Modifier.testTag("export-progress"), fontSize = 12.sp, color = c.textSecondary)
                    job.error?.let { Text(it, Modifier.testTag("export-error"), fontSize = 12.sp, color = c.error) }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("Vista previa:")
                    Row(Modifier.weight(1f).fillMaxWidth().background(c.editorBg).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        val lines = preview.lines().let { if (it.size > 1 && it.last().isEmpty()) it else it }
                        val mono = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp)
                        Column(Modifier.width(34.dp).padding(end = 8.dp)) {
                            lines.indices.forEach { Text("${it + 1}", Modifier.fillMaxWidth(), style = mono.copy(fontSize = 11.sp), color = c.rowNumber, textAlign = TextAlign.End) }
                        }
                        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("export-preview")) {
                            lines.forEach { Text(it.ifEmpty { " " }, style = mono, color = c.gridText, softWrap = false) }
                        }
                    }
                    job.sampleError?.let { Text(it, fontSize = 12.sp, color = c.error) }
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (job.running) {
                    TextAction("Cancelar exportación", job::cancel, Modifier.testTag("export-cancel"))
                    TextAction("Ocultar", state::closeExport, Modifier.testTag("export-hide"))
                } else TextAction("Cancelar", state::closeExport, Modifier.testTag("export-close"))
                TextAction("Copiar al portapapeles", { job.start(true, { clipboard.setText(AnnotatedString(it)) }, state::closeExport) }, Modifier.testTag("export-clipboard"), enabled = !job.running)
                TextAction("Exportar a archivo", { job.start(false, {}, state::closeExport) }, Modifier.testTag("export-file"), enabled = !job.running, primary = true)
            }
        }
    }
}
