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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.app.ui.*
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SecretStore
import ksqlc.storage.Settings
import ksqlc.storage.secretStoreFor
import java.nio.file.Path

/** Asks for the master password of the encrypted passwords file before the app opens. Tags: `unlock-master`, `unlock-ok`, `unlock-skip`, `unlock-error`. */
@Composable
fun UnlockScreen(settings: Settings, dir: Path, onOpen: (SecretStore) -> Unit) = KsqlcTheme(settings.themeMode) {
    val c = Ksqlc.colors
    val scope = rememberCoroutineScope()
    var master by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun unlock() {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { secretStoreFor(settings, dir, master.toCharArray()) } }
            busy = false
            r.onSuccess(onOpen).onFailure { error = it.message ?: it.toString() }
        }
    }

    Box(Modifier.fillMaxSize().background(c.windowBg), contentAlignment = Alignment.Center) {
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Contraseñas protegidas", fontSize = 14.sp, color = c.text)
            FieldLabel("Contraseña maestra")
            CompactTextField(master, { master = it }, Modifier.fillMaxWidth(), password = true, testTag = "unlock-master")
            error?.let { Text(it, fontSize = 12.sp, color = c.error, modifier = Modifier.testTag("unlock-error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                // the file stays untouched; passwords typed this session live in memory only
                TextAction("Continuar sin contraseñas", { onOpen(InMemorySecretStore()) }, Modifier.testTag("unlock-skip"))
                TextAction("Desbloquear", ::unlock, Modifier.testTag("unlock-ok"), enabled = !busy && master.isNotEmpty(), primary = true)
            }
        }
    }
}
