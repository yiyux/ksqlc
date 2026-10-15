package ksqlc.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*

/** A run waiting for the values of [names] (shown as typed in the SQL); [onRun] gets them in the same order. */
class ParamPrompt(val names: List<String>, val initial: List<String>, val onRun: (List<String>) -> Unit)

/** Asks for the value of each parameter before a run. Values are SQL: `5`, `'text'`, `now()`, `NULL` (tags `param-dialog`, `param:i`, `param-ok`, `param-cancel`). */
@Composable
fun ParamDialog(state: AppState) {
    val p = state.paramPrompt ?: return
    val c = Ksqlc.colors
    val values = remember(p) { mutableStateListOf<String>().apply { addAll(p.initial) } }
    val ready = values.all { it.isNotBlank() }
    fun run() { if (ready) { state.paramPrompt = null; p.onRun(values.toList()) } }
    val first = remember(p) { FocusRequester() }
    LaunchedEffect(p) { runCatching { first.requestFocus() } }
    ModalOverlay({ state.paramPrompt = null }, Modifier.testTag("param-dialog"), width = 460.dp, height = (150 + 54 * p.names.size).dp.coerceAtMost(520.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Parámetros", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text("Escribe cada valor como SQL: 5, 'texto', now(), NULL.", fontSize = 12.sp, color = c.textSecondary)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.names.forEachIndexed { i, name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, Modifier.width(110.dp), fontSize = 13.sp, color = c.text, maxLines = 1)
                        CompactTextField(
                            values[i], { values[i] = it },
                            Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier).onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { run(); true } else false
                            },
                            testTag = "param:$i", height = 28.dp,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", { state.paramPrompt = null }, Modifier.testTag("param-cancel"))
                TextAction("Ejecutar", ::run, Modifier.testTag("param-ok"), enabled = ready, primary = true)
            }
        }
    }
}
