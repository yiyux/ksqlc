package ksqlc.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
import ksqlc.storage.PasswordStorage
import ksqlc.storage.SettingsStore
import ksqlc.storage.secretStoreFor
import java.nio.file.Path

fun main() = application {
    val scope = rememberCoroutineScope()
    val dir = remember { Path.of(System.getProperty("user.home"), ".ksqlc") }
    val settingsStore = remember { SettingsStore.default() }
    val settings = remember { settingsStore.load() }
    fun open(secrets: ksqlc.storage.SecretStore) = Workspace(scope, ConnectionStore(dir, secrets), settingsStore, HistoryStore.default())
    // with an encrypted file the workspace waits for the master password, so nothing reads secrets before then
    var ws by remember { mutableStateOf(if (settings.passwordStorage == PasswordStorage.FILE) null else open(secretStoreFor(settings, dir, CharArray(0)))) }
    DisposableEffect(ws) { val w = ws; onDispose { w?.dispose() } }
    Window(
        // closing with unsent edits or an open transaction asks first
        onCloseRequest = { ws?.requestExit(::exitApplication) ?: exitApplication() },
        title = "ksqlc",
        icon = painterResource("icon.png"),
        state = rememberWindowState(size = DpSize(1250.dp, 800.dp)),
    ) {
        ws?.let { App(it.active, it) } ?: UnlockScreen(settings, dir) { ws = open(it) }
    }
}
