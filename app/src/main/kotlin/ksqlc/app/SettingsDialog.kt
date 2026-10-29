package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.I18n
import ksqlc.core.Language
import ksqlc.core.tr
import ksqlc.storage.PasswordStorage
import java.nio.file.Path
import kotlin.io.path.exists

/** App settings. Sections in [SECTIONS]. Tags: `settings-dialog`, `settings-section-<i>`, `lang-es`, `lang-en`, `pw-keychain`, `pw-file`, `pw-none`, `pw-master`, `pw-repeat`, `settings-apply`. */
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
    var section by remember { mutableStateOf(0) }
    var lang by remember { mutableStateOf(cur.language ?: I18n.language) }
    val close = { state.settingsDialog = false }
    val isNew = mode == PasswordStorage.FILE && !runCatching { Path.of(file).exists() }.getOrDefault(false)
    val unchanged = mode == cur.passwordStorage && (mode != PasswordStorage.FILE || file == cur.passwordFile.ifBlank { state.dataDir.resolve("secrets.enc").toString() })

    fun apply() {
        // the language only applies on the next start; I18n.language stays as it is
        if (lang != (cur.language ?: I18n.language)) state.updateSettings { it.copy(language = lang) }
        if (unchanged) { close(); return }
        if (mode == PasswordStorage.FILE) {
            if (file.isBlank()) { error = tr("Elige el archivo."); return }
            if (master.isEmpty()) { error = tr("Escribe la contraseña maestra."); return }
            if (isNew && master != repeat) { error = tr("Las contraseñas maestras no coinciden."); return }
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
            PanelHeader(tr("Ajustes")) { ToolButton(IconKind.Close, tr("Cerrar"), close) }
            Separator()
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(170.dp).fillMaxHeight().padding(8.dp)) {
                    SECTIONS.forEachIndexed { i, name ->
                        Text(
                            tr(name), fontSize = 13.sp, color = c.text,
                            modifier = Modifier.fillMaxWidth().testTag("settings-section-$i")
                                .then(if (i == section) Modifier.background(c.selectionInactive) else Modifier)
                                .clickable { section = i }.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                if (section == 1) Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Idioma:"), fontSize = 13.sp, color = c.text)
                    KRadio(lang == Language.ES, { lang = Language.ES }, "Español", Modifier.testTag("lang-es"))
                    KRadio(lang == Language.EN, { lang = Language.EN }, "English (US)", Modifier.testTag("lang-en"))
                    Text(tr("El cambio se aplica al reiniciar la aplicación."), fontSize = 12.sp, color = c.textMuted)
                } else Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Guardar contraseñas:"), fontSize = 13.sp, color = c.text)
                    KRadio(mode == PasswordStorage.KEYCHAIN, { mode = PasswordStorage.KEYCHAIN }, tr("En el llavero del sistema"), Modifier.testTag("pw-keychain"))
                    KRadio(mode == PasswordStorage.FILE, { mode = PasswordStorage.FILE }, tr("En un archivo cifrado"), Modifier.testTag("pw-file"))
                    if (mode == PasswordStorage.FILE) Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(tr("Archivo:"), Modifier.width(120.dp))
                            CompactTextField(file, { file = it }, Modifier.weight(1f), testTag = "pw-path")
                            ToolButton(IconKind.Folder, tr("Elegir archivo…"), { chooseFile(true, tr("Archivo de contraseñas"), "secrets.enc")?.let { file = it } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(tr("Contraseña maestra:"), Modifier.width(120.dp))
                            CompactTextField(master, { master = it }, Modifier.weight(1f), password = true, testTag = "pw-master")
                        }
                        if (isNew) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(tr("Repetir:"), Modifier.width(120.dp))
                            CompactTextField(repeat, { repeat = it }, Modifier.weight(1f), password = true, testTag = "pw-repeat")
                        }
                        Text(
                            tr("Cifrado con AES-256-GCM; la clave sale de la contraseña maestra, que no se guarda en ningún sitio. Si la olvidas, las contraseñas de ese archivo no se pueden recuperar."),
                            fontSize = 12.sp, color = c.textMuted,
                        )
                    }
                    KRadio(mode == PasswordStorage.NONE, { mode = PasswordStorage.NONE }, tr("No guardar, olvidar las contraseñas al reiniciar"), Modifier.testTag("pw-none"))
                    error?.let { Text(it, fontSize = 12.sp, color = c.error, modifier = Modifier.testTag("settings-error")) }
                }
            }
            Separator()
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction(tr("Cancelar"), close, Modifier.testTag("settings-cancel"))
                TextAction(tr("Aplicar"), ::apply, Modifier.testTag("settings-apply"), enabled = !busy, primary = true)
            }
        }
    }
}

private val SECTIONS = listOf("Contraseñas", "Interfaz")
