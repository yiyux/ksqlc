package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind

private fun DbObject.qualified() = (ns.schema ?: ns.catalog)?.let { "$it." }.orEmpty() + name

/**
 * [objects] whose `schema.name` holds every word of [query] (any case), best first: names that start with the first word,
 * then names that contain it, then matches on the schema only; ties by name. An empty query lists everything. At most [limit].
 */
fun searchObjects(objects: List<DbObject>, query: String, limit: Int = 200): List<DbObject> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun rank(o: DbObject): Int? {
        val full = o.qualified().lowercase()
        if (!words.all { it in full }) return null
        val name = o.name.lowercase()
        return when {
            words.isEmpty() || name.startsWith(words[0]) -> 0
            words[0] in name -> 1
            else -> 2
        }
    }
    return objects.mapNotNull { o -> rank(o)?.let { it to o } }
        .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }, { it.second.qualified().lowercase() }))
        .take(limit).map { it.second }
}

/** "Go to table": type part of a name, pick with ↑↓ and Enter (opens the data) or ⇧Enter (shows it in the explorer). Tags `object-search`, `object-search-input`, `object-hit:i`. */
@Composable
fun ObjectSearchDialog(state: AppState) {
    if (!state.objectSearch) return
    val c = Ksqlc.colors
    val all = remember(state.catalog, state.catalog?.version) { state.catalog?.all().orEmpty() }
    var query by remember { mutableStateOf("") }
    val hits = remember(all, query) { searchObjects(all, query) }
    var selected by remember(hits) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(selected) { if (hits.isNotEmpty()) list.scrollToItem((selected - 3).coerceAtLeast(0)) }
    fun pick(i: Int, reveal: Boolean) { hits.getOrNull(i)?.let { state.pickObject(it, reveal) } }
    ModalOverlay({ state.objectSearch = false }, Modifier.testTag("object-search"), width = 560.dp, height = 420.dp) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactTextField(
                query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(hits.lastIndex.coerceAtLeast(0)); true }
                        Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { pick(selected, e.isShiftPressed); true }
                        Key.Escape -> { state.objectSearch = false; true }
                        else -> false
                    }
                },
                placeholder = "Buscar tabla o vista", testTag = "object-search-input", height = 30.dp,
            )
            if (state.catalog == null) Text("Conecta para buscar objetos.", fontSize = 13.sp, color = c.textMuted)
            else if (hits.isEmpty()) Text("Sin resultados", fontSize = 13.sp, color = c.textMuted, modifier = Modifier.testTag("object-search-empty"))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                itemsIndexed(hits) { i, o ->
                    Row(
                        Modifier.fillMaxWidth().height(26.dp).background(if (i == selected) c.selection else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { pick(i, false) }.padding(horizontal = 8.dp).testTag("object-hit:$i"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KIcon(when (o.kind) { DbObjectKind.VIEW -> IconKind.View; DbObjectKind.MATERIALIZED_VIEW -> IconKind.MatView; else -> IconKind.Table }, tint = c.accentIcon, size = 14.dp)
                        Text(o.name, fontSize = 13.sp, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(o.ns.label, fontSize = 12.sp, color = c.textMuted, maxLines = 1)
                    }
                }
            }
            Text("↵ abrir datos · ⇧↵ mostrar en el explorador · Esc cerrar", fontSize = 11.sp, color = c.textMuted)
        }
    }
}
