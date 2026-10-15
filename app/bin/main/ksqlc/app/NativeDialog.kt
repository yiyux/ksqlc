package ksqlc.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.DbKind

/** Dump or restore with the engine's own tool (tags `native-dialog`, `native-tool`, `native-file`, `native-run`, `native-error`). */
@Composable
fun NativeDialog(state: AppState) {
    val job = state.nativeJob ?: return
    val c = Ksqlc.colors
    val found = job.found
    val pg = job.kind == DbKind.POSTGRES
    ModalOverlay(state::closeNative, Modifier.testTag("native-dialog"), width = 640.dp, height = if (job.restore) 360.dp else 440.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(if (job.restore) "Restaurar con la herramienta del motor" else "Dump con la herramienta del motor") { ToolButton(IconKind.Close, "Cerrar", state::closeNative) }
            Separator()
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!job.restore && job.scopes.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ámbito:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    SegmentedControl(job.scopes.map { it.first }, job.scopeIndex, { job.scopeIndex = it }, tag = "native-scope")
                } else if (!job.restore) Text("Ámbito: ${job.scopes.first().first}", fontSize = 13.sp, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Herramienta:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    CompactTextField(
                        job.toolPath, { job.toolPath = it }, Modifier.weight(1f), testTag = "native-tool", height = 28.dp,
                        placeholder = found?.toString() ?: "no se encuentra ${job.toolNames.joinToString(" ni ")}",
                    )
                }
                if (found == null) Text("Instálala (por ejemplo con Homebrew) o escribe su ruta completa.", Modifier.testTag("native-missing"), fontSize = 12.sp, color = c.warning)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Archivo:", Modifier.width(90.dp), fontSize = 13.sp, color = c.text)
                    CompactTextField(job.file, { job.file = it }, Modifier.weight(1f), testTag = "native-file", height = 28.dp, placeholder = if (job.restore) "archivo .sql o .dump" else "destino del volcado")
                    TextAction("…", { chooseFile(!job.restore, if (job.restore) "Restaurar" else "Guardar dump", if (job.restore) null else "dump.sql")?.let { job.file = it } })
                }
                if (!job.restore) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.width(90.dp - 16.dp))
                        KCheckbox(job.schemaOnly, { job.schemaOnly = it; if (it) job.dataOnly = false }, label = "Solo esquema", modifier = Modifier.testTag("native-schema-only"))
                        KCheckbox(job.dataOnly, { job.dataOnly = it; if (it) job.schemaOnly = false }, label = "Solo datos", enabled = job.kind != DbKind.SQLITE, modifier = Modifier.testTag("native-data-only"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.width(90.dp - 16.dp))
                        KCheckbox(job.clean, { job.clean = it }, label = "Incluir DROP antes de crear", enabled = job.kind != DbKind.SQLITE)
                    }
                }
                if (pg) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.width(90.dp - 16.dp))
                    KCheckbox(job.custom, job::onCustomChanged, label = if (job.restore) "Formato personalizado (pg_restore)" else "Formato personalizado (-F c)", modifier = Modifier.testTag("native-custom"))
                }
                Text(
                    if (job.restore) "La salida de la herramienta aparece en Output. Usa una herramienta de la misma versión mayor que el servidor o más nueva para volcar, y del servidor o más nueva para restaurar."
                    else "La salida de la herramienta aparece en Output. La contraseña se pasa por variable de entorno, no en la línea de comandos.",
                    fontSize = 12.sp, color = c.textSecondary,
                )
                if (job.running) Text("En curso…", Modifier.testTag("native-progress"), fontSize = 12.sp, color = c.textSecondary)
                job.error?.let { Text(it, Modifier.testTag("native-error"), fontSize = 12.sp, color = c.error) }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (job.running) TextAction("Cancelar", job::cancel, Modifier.testTag("native-cancel")) else TextAction("Cerrar", state::closeNative)
                val go = { job.start { state.nativeFinished(job.restore) } }
                TextAction(
                    if (job.restore) "Restaurar" else "Volcar",
                    {
                        if (job.restore && job.file.isNotBlank() && found != null)
                            state.confirmation = Confirmation("Restaurar", "Se ejecutará ${job.file} sobre la base de datos activa (${state.form.name}). Puede sobrescribir datos.", "Restaurar") { state.confirmation = null; go() }
                        else go()
                    },
                    Modifier.testTag("native-run"), enabled = !job.running, primary = true,
                )
            }
        }
    }
}
