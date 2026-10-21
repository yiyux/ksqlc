package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.storage.PasswordStorage
import java.nio.file.Path
import kotlin.io.path.exists

/** App settings. One section for now, "Contraseñas"; more go in [SECTIONS]. Tags: `settings-dialog`, `pw-keychain`, `pw-file`, `pw-none`, `pw-master`, `pw-repeat`, `settings-apply`. */
@Composable
fun SettingsDialog(state: AppState) {
    val c = Ksqlc.colors
    val cur = state.settings
    var mode by remember { mutableStateOf(cur.passwordStorage) }
    var file by remember { mutableStateOf(cur.passwordFile.ifBlank { state.dataDir.resolve("secrets.enc").toString() }) }
    var master by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val close = { state.settingsDialog = false }
    val isNew = mode == PasswordStorage.FILE && !runCatching { Path.of(file).exists() }.getOrDefault(false)
    val unchanged = mode == cur.passwordStorage && (mode != PasswordStorage.FILE || file == cur.passwordFile.ifBlank { state.dataDir.resolve("secrets.enc").toString() })

    fun apply() {
        if (!unchanged && mode == PasswordStorage.FILE) {
            if (file.isBlank()) { error = "Elige el archivo."; return }
            if (master.isEmpty()) { error = "Escribe la contraseña maestra."; return }
            if (isNew && master != repeat) { error = "Las contraseñas maestras no coinciden."; return }
        }
        busy = true; error = null
        // the default location is stored as blank so it follows the data folder
        val stored = if (file == state.dataDir.resolve("secrets.enc").toString()) "" else file
        state.applyPasswordStorage(mode, stored, master.toCharArray()) { err ->
            busy = false
            if (err == null) close() else error = err
        }
    }

    ModalOverlay(close, Modifier.testTag("settings-dialog"), width = 780.dp, height = 430.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("Ajustes") { ToolButton(IconKind.Close, "Cerrar", close) }
            Separator()
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(170.dp).fillMaxHeight().padding(8.dp)) {
                    SECTIONS.forEach { name ->
                        Text(
                            name, fontSize = 13.sp, color = c.text,
                            modifier = Modifier.fillMaxWidth().background(c.selectionInactive).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Guardar contraseñas:", fontSize = 13.sp, color = c.text)
                    KRadio(mode == PasswordStorage.KEYCHAIN, { mode = PasswordStorage.KEYCHAIN }, "En el llavero del sistema", Modifier.testTag("pw-keychain"))
                    KRadio(mode == PasswordStorage.FILE, { mode = PasswordStorage.FILE }, "En un archivo cifrado", Modifier.testTag("pw-file"))
                    if (mode == PasswordStorage.FILE) Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("Archivo:", Modifier.width(120.dp))
                            CompactTextField(file, { file = it }, Modifier.weight(1f), testTag = "pw-path")
                            ToolButton(IconKind.Folder, "Elegir archivo…", { chooseFile(true, "Archivo de contraseñas", "secrets.enc")?.let { file = it } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("Contraseña maestra:", Modifier.width(120.dp))
                            CompactTextField(master, { master = it }, Modifier.weight(1f), password = true, testTag = "pw-master")
                        }
                        if (isNew) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("Repetir:", Modifier.width(120.dp))
                            CompactTextField(repeat, { repeat = it }, Modifier.weight(1f), password = true, testTag = "pw-repeat")
                        }
                        Text(
                            "Cifrado con AES-256-GCM; la clave sale de la contraseña maestra, que no se guarda en ningún sitio. Si la olvidas, las contraseñas de ese archivo no se pueden recuperar.",
                            fontSize = 12.sp, color = c.textMuted,
                        )
                    }
                    KRadio(mode == PasswordStorage.NONE, { mode = PasswordStorage.NONE }, "No guardar, olvidar las contraseñas al reiniciar", Modifier.testTag("pw-none"))
                    error?.let { Text(it, fontSize = 12.sp, color = c.error, modifier = Modifier.testTag("settings-error")) }
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", close, Modifier.testTag("settings-cancel"))
                TextAction("Aplicar", ::apply, Modifier.testTag("settings-apply"), enabled = !busy, primary = true)
            }
        }
    }
}

private val SECTIONS = listOf("Contraseñas")
