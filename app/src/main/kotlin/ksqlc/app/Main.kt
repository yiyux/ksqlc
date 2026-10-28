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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
import ksqlc.storage.PasswordStorage
import ksqlc.storage.SettingsStore
import ksqlc.storage.secretStoreFor
import kotlinx.coroutines.launch
import java.awt.Desktop
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
    // closing with unsent edits or an open transaction asks first
    val requestExit = { ws?.requestExit(::exitApplication) ?: exitApplication() }
    // the macOS app menu's Quit (and Cmd+Q) goes through the system handler, not the window's close button
    DisposableEffect(Unit) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler { _, response -> response.cancelQuit(); scope.launch { requestExit() } }
        }
        onDispose {}
    }
    val mac = System.getProperty("os.name").startsWith("Mac")
    Window(
        onCloseRequest = requestExit,
        // Cmd+Q on macOS comes from the system quit handler; elsewhere there is no menu to carry Ctrl+Q
        onPreviewKeyEvent = { e -> (!mac && e.isCtrlPressed && e.key == Key.Q && e.type == KeyEventType.KeyDown).also { if (it) requestExit() } },
        title = "ksqlc",
        icon = painterResource("icon.png"),
        state = rememberWindowState(size = DpSize(1250.dp, 800.dp)),
    ) {
        ws?.let { App(it.active, it) } ?: UnlockScreen(settings, dir) { ws = open(it) }
    }
}
