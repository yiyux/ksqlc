package ksqlc.app

import ksqlc.core.tr
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.Namespace

/** Copies the rows of a table into a table of any saved connection (tags `copy-table-dialog`, `copy-table-name`, `copy-table-hint`, `copy-table-run`). */
@Composable
fun CopyTableDialog(state: AppState) {
    val job = state.copyTableJob ?: return
    val c = Ksqlc.colors
    ModalOverlay(state::closeCopyTable, Modifier.testTag("copy-table-dialog"), width = 760.dp, height = 300.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(tr("Copiar tabla")) { ToolButton(IconKind.Close, tr("Cerrar"), state::closeCopyTable) }
            Separator()
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("De %s · %s.%s", job.leftName, job.sourceObj.ns.label, job.sourceObj.name), fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("A"), fontSize = 13.sp, color = c.textMuted)
                    Chooser(job.picker.sources.getOrNull(job.picker.sourceIndex)?.name ?: "—", job.picker.sources, { it.name }, "copy-table-source", 170.dp, job.picker::chooseSource)
                    Chooser(job.picker.namespaces.getOrNull(job.picker.nsIndex)?.label ?: "—", job.picker.namespaces, Namespace::label, "copy-table-ns", 150.dp, job.picker::chooseNamespace)
                    CompactTextField(job.targetName, { job.targetName = it }, Modifier.width(220.dp), placeholder = tr("nombre de la tabla"), testTag = "copy-table-name", height = 28.dp)
                }
                job.hint?.let { Text(it, Modifier.testTag("copy-table-hint"), fontSize = 12.sp, color = if (job.blocked) c.warning else c.textSecondary) }
                if (job.running) Text(tr("Copiadas %s filas…", job.progress), Modifier.testTag("copy-table-progress"), fontSize = 12.sp, color = c.textMuted)
                job.error?.let { Text(it, Modifier.testTag("copy-table-error"), fontSize = 12.sp, color = c.error) }
                job.message?.let { Text(it, Modifier.testTag("copy-table-done"), fontSize = 12.sp, color = c.success) }
            }
            Spacer(Modifier.weight(1f))
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(tr("Ver SQL"), state::previewCopyTable, Modifier.testTag("copy-table-sql"), enabled = !job.blocked && !job.running)
                if (job.running) TextAction(tr("Cancelar"), job::cancel, Modifier.testTag("copy-table-cancel"))
                else TextAction(tr("Copiar"), state::confirmCopyTable, Modifier.testTag("copy-table-run"), enabled = !job.blocked, primary = true)
                Spacer(Modifier.weight(1f))
                TextAction(tr("Cerrar"), state::closeCopyTable)
            }
        }
    }
}
