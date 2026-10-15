package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import ksqlc.app.ui.*

private val pretty = Json { prettyPrint = true }

/** [text] indented as JSON, or null when it is not valid JSON. */
fun prettyJson(text: String): String? = runCatching { pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrNull()

/** Whether a column of [typeName] holds JSON, which adds a "Formatear" button to the editor. */
fun isJsonType(typeName: String) = typeName.uppercase().let { it == "JSON" || it == "JSONB" }

/** Multi-line editor for one cell, for long text and JSON that the single-line cell editor cannot show. */
@Composable
fun BigCellEditor(title: String, initial: String, json: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = Ksqlc.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalOverlay(onCancel, Modifier.testTag("big-editor"), width = 720.dp, height = 480.dp) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 13.sp, color = c.text)
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(1.dp, c.border, RoundedCornerShape(4.dp)).background(c.editorBg).padding(6.dp)) {
                BasicTextField(
                    value, { value = it; error = null }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).focusRequester(focus).testTag("big-editor-text"),
                    textStyle = ksqlc.app.ui.MonoStyle.copy(color = c.gridText), cursorBrush = SolidColor(c.text),
                )
            }
            error?.let { Text(it, fontSize = 12.sp, color = c.error, modifier = Modifier.testTag("big-editor-error")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (json) TextAction("Formatear JSON", {
                    prettyJson(value.text)?.let { value = TextFieldValue(it, TextRange(it.length)); error = null } ?: run { error = "No es un JSON válido" }
                }, Modifier.testTag("big-editor-format"))
                Spacer(Modifier.weight(1f))
                TextAction("Cancelar", onCancel, Modifier.testTag("big-editor-cancel"))
                TextAction("Aceptar", { onSave(value.text) }, Modifier.testTag("big-editor-ok"), primary = true)
            }
        }
    }
}
