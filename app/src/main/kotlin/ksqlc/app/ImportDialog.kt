package ksqlc.app

import ksqlc.core.tr
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ksqlc.app.ui.*

/** CSV into a table (tags `import-dialog`, `import-path`, `import-header`, `import-run`, `import-map:<n>`, `import-error`). */
@Composable
fun ImportDialog(state: AppState) {
    val job = state.importJob ?: return
    val c = Ksqlc.colors
    // read the start of the file whenever the choices that change how it is read change
    LaunchedEffect(job, job.path, job.delimiter, job.header) { withContext(Dispatchers.IO) { job.loadPreview() } }
    ModalOverlay(state::closeImport, Modifier.testTag("import-dialog"), width = 780.dp, height = 600.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(tr("Importar CSV — %s", job.obj.name)) { ToolButton(IconKind.Close, tr("Cerrar"), state::closeImport) }
            Separator()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Archivo:"), Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    CompactTextField(job.path, { job.path = it }, Modifier.weight(1f), placeholder = tr("ruta del archivo .csv"), testTag = "import-path", height = 28.dp)
                    TextAction("…", { chooseFile(false, tr("Importar CSV"))?.let { job.path = it } }, Modifier.testTag("import-browse"))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Separador:"), Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    SegmentedControl(DELIMITERS.map { it.second }, job.delimiter, { job.delimiter = it }, tag = "import-sep")
                    Spacer(Modifier.width(12.dp))
                    KCheckbox(job.header, { job.header = it }, label = tr("La primera fila es la cabecera"), modifier = Modifier.testTag("import-header"))
                }
                if (job.preview.isEmpty()) Text(tr("Elige un archivo para ver su contenido."), fontSize = 12.sp, color = c.textMuted)
                else {
                    Text(tr("Vista previa"), fontSize = 12.sp, color = c.textSecondary)
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        job.preview.forEachIndexed { r, rec ->
                            Row {
                                repeat(job.fileColumnCount) { i ->
                                    Text(
                                        rec.getOrNull(i) ?: "∅", Modifier.width(130.dp).padding(end = 8.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (job.header && r == 0) c.text else c.textSecondary, fontWeight = if (job.header && r == 0) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                                    )
                                }
                            }
                        }
                    }
                    Separator()
                    Text(tr("Columnas"), fontSize = 12.sp, color = c.textSecondary)
                    repeat(job.fileColumnCount) { i ->
                        var open by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(job.fileColumnName(i), Modifier.width(200.dp), fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("→", fontSize = 13.sp, color = c.textMuted)
                            Box(Modifier.width(240.dp)) {
                                val target = job.mapping.getOrNull(i)
                                DropdownField(target?.let { job.columns[it].name } ?: tr("(omitir)"), { open = true }, Modifier.fillMaxWidth().testTag("import-map:$i"))
                                DropdownMenu(open, { open = false }) {
                                    DropdownMenuItem(text = { Text(tr("(omitir)"), fontSize = 13.sp) }, onClick = { open = false; job.mapping[i] = null })
                                    job.columns.forEachIndexed { j, col ->
                                        DropdownMenuItem(text = { Text("${col.name}  ${col.typeName}", fontSize = 13.sp) }, modifier = Modifier.testTag("import-map:$i:$j"), onClick = { open = false; job.mapping[i] = j })
                                    }
                                }
                            }
                        }
                    }
                }
                Text(tr("Un campo vacío entra como NULL. Todo se inserta en una transacción: si una fila falla, no se guarda ninguna."), fontSize = 12.sp, color = c.textSecondary)
                if (job.running) Text(tr("%s filas…", job.progress), Modifier.testTag("import-progress"), fontSize = 12.sp, color = c.textSecondary)
                job.error?.let { Text(it, Modifier.testTag("import-error"), fontSize = 12.sp, color = c.error) }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction(tr("Cerrar"), state::closeImport, enabled = !job.running)
                TextAction(tr("Importar"), { job.start { state.importFinished(job.obj) } }, Modifier.testTag("import-run"), enabled = !job.running && job.preview.isNotEmpty(), primary = true)
            }
        }
    }
}
