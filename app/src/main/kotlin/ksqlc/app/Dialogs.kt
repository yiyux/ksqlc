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
import ksqlc.drivers.supportsSingleDatabase
import ksqlc.drivers.supportsTimeZone
import ksqlc.drivers.supportsTransactions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import ksqlc.drivers.JdbcUrl

private fun newDraft() = ConnectionForm().apply { name = "Nueva conexión"; kind = DbKind.POSTGRES; portText = "5432"; user = "" }

@Composable
private fun Field(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(120.dp), fontSize = 13.sp, color = Ksqlc.colors.text, maxLines = 1)
        content()
    }
}

/** A label wide enough for the option names, then the control. */
@Composable
private fun OField(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(210.dp), fontSize = 13.sp, color = Ksqlc.colors.text)
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    val c = Ksqlc.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Separator(Modifier.weight(1f))
    }
}

/**
 * The "Opciones" tab: connection behaviour and how the schema is read. Each engine only gets the options it can honour
 * (a time zone for PostgreSQL, MySQL, MariaDB and Oracle; no transaction control for Cassandra; …). Tags `ds-readonly`, `ds-tx`,
 * `ds-tz`, `ds-keepalive[-sec]`, `ds-autodisconnect[-sec]`, `ds-single`, `ds-startup`, `ds-autosync`, `ds-introspect`.
 */
@Composable
private fun OptionsTab(draft: ConnectionForm, modifier: Modifier) {
    val c = Ksqlc.colors
    val kind = draft.effectiveKind
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Conexión")
        KCheckbox(draft.readOnly, { draft.readOnly = it }, label = "Solo lectura", modifier = Modifier.testTag("ds-readonly"))
        if (supportsTransactions(kind)) OField("Control de transacciones:") {
            SegmentedControl(listOf("Auto", "Manual"), if (draft.manualTx) 1 else 0, { draft.manualTx = it == 1 }, tag = "ds-tx")
        }
        if (supportsTimeZone(kind)) OField("Zona horaria:") {
            CompactTextField(draft.timeZone, { draft.timeZone = it }, Modifier.weight(1f), placeholder = "p. ej. Europe/Madrid o +01:00; vacía = la del servidor", testTag = "ds-tz", height = 28.dp)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KCheckbox(draft.keepAliveOn, { draft.keepAliveOn = it }, label = "Consulta de keep-alive cada", modifier = Modifier.testTag("ds-keepalive"))
            CompactTextField(draft.keepAliveText, { draft.keepAliveText = it.filter(Char::isDigit) }, Modifier.width(80.dp), enabled = draft.keepAliveOn, testTag = "ds-keepalive-sec", height = 28.dp)
            Text("s", fontSize = 13.sp, color = c.text)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KCheckbox(draft.autoDisconnectOn, { draft.autoDisconnectOn = it }, label = "Cerrar la consola tras", modifier = Modifier.testTag("ds-autodisconnect"))
            CompactTextField(draft.autoDisconnectText, { draft.autoDisconnectText = it.filter(Char::isDigit) }, Modifier.width(80.dp), enabled = draft.autoDisconnectOn, testTag = "ds-autodisconnect-sec", height = 28.dp)
            Text("s sin uso", fontSize = 13.sp, color = c.text)
        }
        if (supportsSingleDatabase(kind)) KCheckbox(
            draft.singleDatabase, { draft.singleDatabase = it },
            label = when (kind) { DbKind.CASSANDRA -> "Modo de un solo keyspace"; DbKind.ORACLE -> "Mostrar solo el esquema del usuario"; else -> "Modo de una sola base de datos" },
            modifier = Modifier.testTag("ds-single"),
        )
        OField("Script de inicio:") {
            BasicTextField(
                draft.startupScript, { draft.startupScript = it },
                Modifier.weight(1f).height(90.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, c.border, RoundedCornerShape(4.dp)).background(c.editorBg).padding(6.dp).testTag("ds-startup"),
                textStyle = MonoStyle.copy(color = c.text, fontSize = 12.sp), cursorBrush = SolidColor(c.text),
            )
        }
        Text("Se ejecuta cada vez que se abre una conexión (consola y pestañas de tabla).", Modifier.padding(start = 220.dp), fontSize = 11.sp, color = c.textMuted)
        SectionTitle("Introspección")
        KCheckbox(draft.autoSync, { draft.autoSync = it }, label = "Sincronizar el explorador tras cambiar el esquema", modifier = Modifier.testTag("ds-autosync"))
        OField("Intervalo de introspección automática:") {
            CompactTextField(draft.introspectionText, { draft.introspectionText = it.filter(Char::isDigit) }, Modifier.width(80.dp), testTag = "ds-introspect", height = 28.dp)
            Text("minutos (vacío = nunca)", fontSize = 13.sp, color = c.text)
        }
    }
}

/**
 * The "Esquemas" tab: which schemas (databases, users, keyspaces) the explorer lists. The list to choose from is read from the server
 * on demand with the connection as it is in the form. Tags `ds-schemas-mode:i`, `ds-schema-pattern`, `ds-schemas-load`, `ds-schemas-error`, `ds-schema:<name>`.
 */
@Composable
private fun SchemasTab(state: AppState, draft: ConnectionForm, modifier: Modifier) {
    val c = Ksqlc.colors
    var loaded by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val chosen = draft.schemasSelected
    val names = (loaded + chosen).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val picking = !draft.schemasAll
    Column(modifier.padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OField("Mostrar:") { SegmentedControl(listOf("Todos", "Solo los elegidos"), if (draft.schemasAll) 0 else 1, { draft.schemasAll = it == 0 }, tag = "ds-schemas-mode") }
        OField("Patrón:") {
            CompactTextField(draft.schemaPattern, { draft.schemaPattern = it }, Modifier.weight(1f), placeholder = "p. ej. app_*, ventas (con * y ?; vacío = ninguno)", testTag = "ds-schema-pattern", height = 28.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction(if (loading) "Leyendo…" else "Leer esquemas del servidor", {
                loading = true; error = null
                state.loadNamespaces(draft) { r -> loading = false; r.onSuccess { loaded = it }.onFailure { error = it.message ?: it.toString() } }
            }, Modifier.testTag("ds-schemas-load"), enabled = !loading)
            if (picking) Text("${chosen.size} elegidos", fontSize = 12.sp, color = c.textSecondary)
        }
        error?.let { Text(it, fontSize = 12.sp, color = c.error, modifier = Modifier.testTag("ds-schemas-error")) }
        if (names.isEmpty()) Text("Lee los esquemas del servidor para elegir cuáles mostrar; los de sistema también salen en la lista.", fontSize = 12.sp, color = c.textMuted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(1.dp, c.border, RoundedCornerShape(4.dp))) {
            items(names, key = { it }) { name ->
                Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KCheckbox(
                        chosen.any { it.equals(name, true) },
                        { on -> draft.schemasSelected = if (on) chosen + name else chosen.filterNot { it.equals(name, true) } },
                        label = name, enabled = picking, modifier = Modifier.testTag("ds-schema:$name"),
                    )
                }
            }
        }
    }
}

/** Which of the four SSL modes (off, encrypted, verify CA, verify CA and name) an engine offers. */
private fun sslModesFor(kind: DbKind): List<String> = when (kind) {
    DbKind.POSTGRES, DbKind.MYSQL, DbKind.MARIADB, DbKind.MSSQL -> listOf("Desactivado", "Cifrado", "Verificar CA", "CA y host")
    DbKind.CASSANDRA -> listOf("Desactivado", "Cifrado")
    else -> emptyList()
}

/**
 * The "SSH/SSL" tab. Tags `ds-ssh-on`, `ds-ssh-host`, `ds-ssh-port`, `ds-ssh-user`, `ds-ssh-auth:i`, `ds-ssh-secret`, `ds-ssh-key`, `ds-ssh-browse`.
 */
@Composable
private fun SshSslTab(draft: ConnectionForm, modifier: Modifier) {
    val c = Ksqlc.colors
    val network = draft.effectiveKind != DbKind.SQLITE
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Túnel SSH")
        if (!network) Text("SQLite abre un archivo local: no usa red.", fontSize = 13.sp, color = c.textMuted)
        else if (draft.customUrl) Text("El túnel necesita una conexión definida con host y puerto; con «Solo URL» no está disponible.", fontSize = 13.sp, color = c.textMuted)
        else {
            val on = draft.sshEnabled
            KCheckbox(draft.sshEnabled, { draft.sshEnabled = it }, label = "Usar túnel SSH", modifier = Modifier.testTag("ds-ssh-on"))
            OField("Host:") {
                CompactTextField(draft.sshHost, { draft.sshHost = it }, Modifier.weight(1f), enabled = on, placeholder = "servidor intermedio", testTag = "ds-ssh-host", height = 28.dp)
                Text("Puerto:", fontSize = 13.sp, color = c.text)
                CompactTextField(draft.sshPortText, { draft.sshPortText = it.filter(Char::isDigit) }, Modifier.width(80.dp), enabled = on, testTag = "ds-ssh-port", height = 28.dp)
            }
            OField("Usuario:") { CompactTextField(draft.sshUser, { draft.sshUser = it }, Modifier.weight(1f), enabled = on, testTag = "ds-ssh-user", height = 28.dp) }
            OField("Autenticación:") {
                SegmentedControl(listOf("Contraseña", "Clave privada"), if (draft.sshAuth == ksqlc.core.SshAuth.KEY) 1 else 0, { draft.sshAuth = if (it == 1) ksqlc.core.SshAuth.KEY else ksqlc.core.SshAuth.PASSWORD }, tag = "ds-ssh-auth")
            }
            if (draft.sshAuth == ksqlc.core.SshAuth.KEY) OField("Archivo de clave:") {
                CompactTextField(draft.sshKeyFile, { draft.sshKeyFile = it }, Modifier.weight(1f), enabled = on, placeholder = "~/.ssh/id_ed25519", testTag = "ds-ssh-key", height = 28.dp)
                ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(false, "Clave privada SSH")?.let { draft.sshKeyFile = it } }, Modifier.testTag("ds-ssh-browse"), enabled = on)
            }
            OField(if (draft.sshAuth == ksqlc.core.SshAuth.KEY) "Frase de la clave:" else "Contraseña:") {
                CompactTextField(draft.sshSecret, { draft.sshSecret = it }, Modifier.weight(1f), enabled = on, password = true, placeholder = "<oculta>", testTag = "ds-ssh-secret", height = 28.dp)
            }
            Text(
                "La base de datos se alcanza desde el servidor SSH (el host y puerto de General son los que ve ese servidor). Un servidor nuevo se confirma la primera vez y su clave se guarda en ~/.ksqlc/known_hosts. La contraseña se guarda en el llavero.",
                Modifier.padding(start = 220.dp), fontSize = 11.sp, color = c.textMuted,
            )
        }
        SectionTitle("SSL")
        val modes = sslModesFor(draft.effectiveKind)
        if (modes.isEmpty()) Text(if (network) "Oracle necesita un listener TCPS y una cartera de certificados, que aquí no se configuran." else "SQLite abre un archivo local: no usa red.", fontSize = 13.sp, color = c.textMuted)
        else if (draft.customUrl) Text("Con «Solo URL» los parámetros SSL van en la propia URL.", fontSize = 13.sp, color = c.textMuted)
        else {
            OField("Modo:") { SegmentedControl(modes, draft.sslMode.coerceAtMost(modes.lastIndex), { draft.sslMode = it }, tag = "ds-ssl-mode") }
            val verifying = draft.sslMode >= 2
            val kind = draft.effectiveKind
            if (modes.size > 2) {
                OField("Certificado de la CA:") {
                    CompactTextField(draft.sslCa, { draft.sslCa = it }, Modifier.weight(1f), enabled = verifying, placeholder = "ca.pem (PEM)", testTag = "ds-ssl-ca", height = 28.dp)
                    ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(false, "Certificado de la CA")?.let { draft.sslCa = it } }, Modifier.testTag("ds-ssl-ca-browse"), enabled = verifying)
                }
                if (kind != DbKind.MSSQL) {
                    OField("Certificado de cliente:") {
                        CompactTextField(draft.sslCert, { draft.sslCert = it }, Modifier.weight(1f), enabled = draft.sslMode != 0, placeholder = "opcional (PEM)", testTag = "ds-ssl-cert", height = 28.dp)
                        ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(false, "Certificado de cliente")?.let { draft.sslCert = it } }, Modifier.testTag("ds-ssl-cert-browse"), enabled = draft.sslMode != 0)
                    }
                    OField("Clave de cliente:") {
                        CompactTextField(draft.sslKey, { draft.sslKey = it }, Modifier.weight(1f), enabled = draft.sslMode != 0, placeholder = "opcional (PEM sin frase de paso)", testTag = "ds-ssl-key", height = 28.dp)
                        ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(false, "Clave de cliente")?.let { draft.sslKey = it } }, Modifier.testTag("ds-ssl-key-browse"), enabled = draft.sslMode != 0)
                    }
                }
            }
            Text(
                when (kind) {
                    DbKind.CASSANDRA -> "Cassandra cifra con el almacén de confianza de Java; no se comprueban archivos propios."
                    DbKind.MSSQL -> "«Cifrado» acepta cualquier certificado del servidor; «Verificar» lo comprueba con la CA indicada, o con el almacén de Java si no hay ninguna."
                    else -> "«Cifrado» no comprueba el certificado del servidor. «Verificar CA» lo comprueba con la CA indicada; «y nombre» exige además que el certificado sea para el host."
                } + if (draft.sshEnabled) " Con túnel SSH el nombre que se comprueba es 127.0.0.1, el extremo local del túnel." else "",
                Modifier.padding(start = 220.dp), fontSize = 11.sp, color = c.textMuted,
            )
        }
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
    var tab by remember { mutableStateOf(0) }
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
                                scope.launch {
                                    draft.password = withContext(Dispatchers.IO) { state.storedPassword(ds.id) }.orEmpty()
                                    draft.sshSecret = withContext(Dispatchers.IO) { state.storedSshSecret(ds.id) }.orEmpty()
                                }
                            }
                        }
                    }
                }
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                    Field("Nombre:") { CompactTextField(draft.name, { draft.name = it }, Modifier.weight(1f), testTag = "ds-name", height = 28.dp) }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("General", "Opciones", "SSH/SSL", "Esquemas").forEachIndexed { i, label ->
                            val on = tab == i
                            Box(
                                Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).then(if (on) Modifier.background(c.selection).border(1.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { tab = i }.padding(horizontal = 12.dp).testTag("ds-tab:$i"),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, fontSize = 13.sp, color = if (on) c.text else c.textSecondary) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Separator()
                    if (tab == 1) OptionsTab(draft, Modifier.weight(1f))
                    else if (tab == 2) SshSslTab(draft, Modifier.weight(1f))
                    else if (tab == 3) SchemasTab(state, draft, Modifier.weight(1f))
                    else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    fun cancel() { state.confirmation = null; q.onCancel() }
    ModalOverlay(::cancel, Modifier.testTag("confirm-dialog"), width = 460.dp, height = if (q.message.length > 160) 260.dp else 170.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(q.title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text)
            Text(q.message, fontSize = 13.sp, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", ::cancel, Modifier.testTag("confirm-cancel"))
                TextAction(q.confirmLabel, q.onConfirm, Modifier.testTag("confirm-ok"), primary = true)
            }
        }
    }
}
