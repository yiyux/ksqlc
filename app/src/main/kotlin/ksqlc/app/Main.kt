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

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ksqlc",
        state = rememberWindowState(size = DpSize(1250.dp, 800.dp)),
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope, ConnectionStore.default()) }
        DisposableEffect(Unit) { onDispose { state.dispose() } }
        App(state)
    }
}
