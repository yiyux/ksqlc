package ksqlc.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
import ksqlc.storage.SettingsStore

fun main() = application {
    val scope = rememberCoroutineScope()
    val ws = remember { Workspace(scope, ConnectionStore.default(), SettingsStore.default(), HistoryStore.default()) }
    DisposableEffect(Unit) { onDispose { ws.dispose() } }
    Window(
        // closing with unsent edits or an open transaction asks first
        onCloseRequest = { ws.requestExit(::exitApplication) },
        title = "ksqlc",
        state = rememberWindowState(size = DpSize(1250.dp, 800.dp)),
    ) {
        App(ws.active, ws)
    }
}
