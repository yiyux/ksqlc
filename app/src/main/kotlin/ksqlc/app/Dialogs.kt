package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.app.ui.*
import ksqlc.core.DbKind
import ksqlc.drivers.JdbcUrl

private fun newDraft() = ConnectionForm().apply { name = "Nueva conexión"; kind = DbKind.POSTGRES; portText = "5432"; user = "" }

@Composable
private fun Field(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(120.dp), fontSize = 13.sp, color = Ksqlc.colors.text, maxLines = 1)
        content()
    }
}

/**
 * "Data Sources" dialog laid out like the reference: saved connections on the left, name + General tab on the right,
 * link-style "Probar conexión" and Cancelar / Apply / OK at the bottom. Edits go to a draft; Apply saves it and keeps
 * the dialog open, OK saves and closes, Cancelar discards.
 */
@Composable
fun DataSourceDialog(state: AppState) {
    val c = Ksqlc.colors
    val scope = rememberCoroutineScope()
    val draft = remember { ConnectionForm().also { it.copyFrom(state.form) } }
    var test by remember { mutableStateOf<Result<String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    val network = draft.kind != DbKind.SQLITE
    fun close() { state.dataSourceDialog = false }

    ModalOverlay(::close, Modifier.testTag("datasource-dialog"), width = 980.dp, height = 737.dp) {
        Column(Modifier.fillMaxSize().background(c.dialogBg)) {
            Box(Modifier.fillMaxWidth().height(29.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text("Data Sources", fontSize = 13.sp, color = c.textSecondary)
                Box(Modifier.align(Alignment.CenterEnd)) { ToolButton(IconKind.Close, "Cerrar", ::close) }
            }
            Separator()
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(250.dp).fillMaxHeight()) {
                    Text("Data Sources", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ToolButton(IconKind.Plus, "Añadir data source", { draft.copyFrom(newDraft()); test = null })
                        ToolButton(IconKind.Minus, "Eliminar data source", {
                            draft.currentId?.let { state.deleteConnection(it) }
                            draft.copyFrom(newDraft()); test = null
                        }, enabled = draft.currentId != null)
                        ToolButton(IconKind.Copy, "Duplicar data source", {
                            val copy = ConnectionForm().also { it.copyFrom(draft); it.currentId = null; it.name = it.name + " (copia)" }
                            draft.copyFrom(copy); test = null
                        })
                    }
                    Text("Data sources del proyecto", Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp), fontSize = 12.sp, color = c.textSecondary)
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (draft.currentId == null) item(key = "new") { DsRow(draft.name, draft.effectiveKind.label(), selected = true) {} }
                        items(state.saved, key = { it.id }) { ds ->
                            DsRow(ds.name, ds.kind.label(), selected = ds.id == draft.currentId) {
                                draft.load(ds); test = null
                                scope.launch { draft.password = withContext(Dispatchers.IO) { state.storedPassword(ds.id) }.orEmpty() }
                            }
                        }
                    }
                }
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                    Field("Nombre:") { CompactTextField(draft.name, { draft.name = it }, Modifier.weight(1f), testTag = "ds-name", height = 28.dp) }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).background(c.selection).border(1.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("General", fontSize = 13.sp, color = c.text) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Separator()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field("Motor:") {
                            Box(Modifier.weight(1f)) {
                                DropdownField(draft.kind.label(), { kindMenu = true }, Modifier.fillMaxWidth().testTag("ds-kind"), icon = IconKind.Database, enabled = !draft.customUrl)
                                DropdownMenu(kindMenu, { kindMenu = false }) {
                                    DbKind.entries.forEach { k ->
                                        DropdownMenuItem(text = { Text(k.label(), fontSize = 13.sp) }, modifier = Modifier.testTag("kind:${k.name}"), onClick = {
                                            kindMenu = false; draft.kind = k; draft.portText = JdbcUrl.defaultPort(k)?.toString().orEmpty()
                                        })
                                    }
                                }
                            }
                        }
                        Field("Tipo de conexión:") {
                            SegmentedControl(listOf("predeterminado", "Solo URL"), if (draft.customUrl) 1 else 0, { i ->
                                if (i == 1) { draft.urlText = draft.url; draft.customUrl = true } else draft.customUrl = false
                            }, tag = "ds-conn-type")
                        }
                        Field("Host:") {
                            CompactTextField(draft.host, { draft.host = it }, Modifier.weight(1f), enabled = network && !draft.customUrl, testTag = "ds-host", height = 28.dp)
                            Text("Puerto:", fontSize = 13.sp, color = c.text)
                            CompactTextField(draft.portText, { draft.portText = it.filter(Char::isDigit) }, Modifier.width(90.dp), enabled = network && !draft.customUrl, testTag = "ds-port", height = 28.dp)
                        }
                        Field("Usuario:") { CompactTextField(draft.user, { draft.user = it }, Modifier.weight(1f), testTag = "ds-user", height = 28.dp) }
                        Field("Contraseña:") { CompactTextField(draft.password, { draft.password = it }, Modifier.weight(1f), password = true, placeholder = "<oculta>", testTag = "ds-pass", height = 28.dp) }
                        Field(if (draft.kind == DbKind.CASSANDRA) "Keyspace:" else if (network) "Base de datos:" else "Archivo:") {
                            CompactTextField(draft.database, { draft.database = it }, Modifier.weight(1f), enabled = !draft.customUrl, placeholder = if (network) null else "vacío = memoria compartida", testTag = "ds-db", height = 28.dp)
                        }
                        if (draft.kind == DbKind.CASSANDRA) Field("Datacenter local:") {
                            CompactTextField(draft.datacenter, { draft.datacenter = it.trim() }, Modifier.weight(1f), enabled = !draft.customUrl, testTag = "ds-dc", height = 28.dp)
                        }
                        Field("SSL:") { KCheckbox(draft.ssl, { draft.ssl = it }, label = "Exigir conexión cifrada", enabled = network && !draft.customUrl, modifier = Modifier.testTag("ds-ssl")) }
                        if (draft.kind == DbKind.MSSQL) Field("") {
                            // SQL Server servers often use a self-signed certificate, which the driver rejects unless told to trust it
                            KCheckbox(draft.trustCert, { draft.trustCert = it }, label = "Confiar en el certificado del servidor", enabled = draft.ssl && !draft.customUrl, modifier = Modifier.testTag("ds-trust"))
                        }
                        Field("URL:") {
                            Column(Modifier.weight(1f)) {
                                CompactTextField(draft.url, { draft.urlText = it }, Modifier.fillMaxWidth(), readOnly = !draft.customUrl, testTag = "ds-url", height = 28.dp)
                                if (draft.customUrl) Text("Sustituye los ajustes anteriores", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = c.textSecondary)
                            }
                        }
                    }
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkAction(if (testing) "Probando…" else "Probar conexión", {
                    testing = true; test = null
                    state.testConnection(draft) { test = it; testing = false }
                }, enabled = !testing, modifier = Modifier.testTag("ds-test"))
                test?.let { r ->
                    Text(
                        r.fold({ "✓ $it" }, { "✗ ${it.message}" }), Modifier.weight(1f).testTag("ds-test-result"), fontSize = 12.sp,
                        color = if (r.isSuccess) c.success else c.error, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } ?: Text(draft.kind.label(), Modifier.weight(1f), fontSize = 13.sp, color = c.textMuted, maxLines = 1)
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                val btn = Modifier.height(28.dp)
                TextAction("Cancelar", ::close, modifier = btn.testTag("ds-cancel"))
                TextAction("Aplicar", { state.applyDraft(draft) }, modifier = btn.testTag("ds-apply"))
                TextAction("OK", { state.applyDraft(draft); close() }, primary = true, modifier = btn.testTag("ds-ok"))
            }
        }
    }
}

@Composable
private fun DsRow(name: String, kind: String, selected: Boolean, onClick: () -> Unit) {
    val c = Ksqlc.colors
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(4.dp))
            .background(if (selected) c.selection else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 8.dp).testTag("ds-row:$name"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KIcon(IconKind.Database, tint = c.accentIcon)
        Text(name, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(kind, fontSize = 12.sp, color = c.textMuted, maxLines = 1)
    }
}

@Composable
fun DdlDialog(state: AppState) {
    val view = state.ddlView ?: return
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    fun close() { state.ddlView = null }
    ModalOverlay(::close, Modifier.testTag(if (view.heading == "DDL") "ddl-dialog" else "sql-dialog"), width = 700.dp, height = 460.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("${view.heading} — ${view.title}") { ToolButton(IconKind.Close, "Cerrar", ::close) }
            Separator()
            Box(Modifier.weight(1f).fillMaxWidth().background(c.editorBg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer { Text(view.text, style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.gridText) }
            }
            Separator()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Copiar", { clipboard.setText(AnnotatedString(view.text)) })
                TextAction("Abrir en consola", { state.appendToEditor(view.text); close() })
                Spacer(Modifier.weight(1f))
                TextAction("Cerrar", ::close, primary = true)
            }
        }
    }
}

/** A small yes/no question over the window (tags `confirm-dialog`, `confirm-ok`, `confirm-cancel`). */
@Composable
fun ConfirmDialog(state: AppState) {
    val q = state.confirmation ?: return
    val c = Ksqlc.colors
    ModalOverlay({ state.confirmation = null }, Modifier.testTag("confirm-dialog"), width = 420.dp, height = 170.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(q.title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text)
            Text(q.message, fontSize = 13.sp, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", { state.confirmation = null }, Modifier.testTag("confirm-cancel"))
                TextAction(q.confirmLabel, q.onConfirm, Modifier.testTag("confirm-ok"), primary = true)
            }
        }
    }
}
