package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ksqlc.app.ui.*

/** Bottom island: a small tree of what ran on the left, and on the right the Output log and one tab per result grid. */
@Composable
fun ServicesPanel(state: AppState, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val scope = rememberCoroutineScope()
    Column(modifier) {
        PanelHeader("Services") { ToolButton(IconKind.Close, "Ocultar Services", { state.servicesVisible = false }) }
        Separator()
        Row(Modifier.fillMaxSize()) {
            ServicesTree(state, Modifier.width(240.dp).fillMaxHeight())
            Separator(vertical = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OverflowTabs(
                    buildList {
                        add(TabSpec("Output", state.selectedTab == OUTPUT_TAB, "tab:output", IconKind.Index, { state.selectedTab = OUTPUT_TAB }))
                        state.tabs.forEachIndexed { i, t -> add(TabSpec(t.title, state.selectedTab == i, "tab:$i", IconKind.Table, { state.selectedTab = i }, { state.closeTab(i) })) }
                    },
                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), height = 26.dp, spacing = 4.dp,
                    trailing = { state.tabs.getOrNull(state.selectedTab)?.let { t -> ToolButton(IconKind.Export, "Exportar resultado…", { state.exportResult(t) }, Modifier.testTag("export-result")) } },
                )
                Separator()
                val tab = state.tabs.getOrNull(state.selectedTab)
                if (tab == null) OutputLog(state, Modifier.fillMaxSize())
                else Grid(tab, onLoadMore = { scope.launch(Dispatchers.IO) { tab.loadMore(PAGE) } }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ServicesTree(state: AppState, modifier: Modifier) {
    val c = Ksqlc.colors
    @Composable
    fun line(depth: Int, icon: IconKind, text: String, hint: String?, selected: Boolean, tag: String, onClick: (() -> Unit)?) {
        val src = remember { MutableInteractionSource() }
        val hovered = src.collectIsHoveredAsState().value
        Row(
            Modifier.testTag(tag).fillMaxWidth().height(24.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(4.dp))
                .background(if (selected) c.selectionInactive else if (hovered && onClick != null) c.hover else Color.Transparent)
                .hoverable(src).then(if (onClick != null) Modifier.clickable(src, null, onClick = onClick) else Modifier).padding(start = 19.dp * depth),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.width(19.dp))
            KIcon(icon, tint = c.textSecondary)
            Text(text, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (hint != null) Text(hint, fontSize = 12.sp, color = c.textMuted, maxLines = 1)
        }
    }
    Column(modifier.padding(top = 6.dp)) {
        line(0, IconKind.Folder, "Database", null, false, "svc:root", null)
        line(1, IconKind.Database, state.form.name, null, false, "svc:connection", null)
        line(2, IconKind.Play, "console", state.lastMillis?.let { "$it ms" }, state.selectedTab == OUTPUT_TAB, "svc:console") { state.selectedTab = OUTPUT_TAB }
        state.tabs.forEachIndexed { i, t ->
            line(2, IconKind.Table, t.title, "${t.rows.size} filas", state.selectedTab == i, "svc:result:$i") { state.selectedTab = i }
        }
    }
}

@Composable
private fun OutputLog(state: AppState, modifier: Modifier) {
    val c = Ksqlc.colors
    val list = rememberLazyListState()
    LaunchedEffect(state.log.size) { if (state.log.isNotEmpty()) list.animateScrollToItem(state.log.lastIndex) }
    Box(modifier.background(c.editorBg).testTag("output")) {
        if (state.log.isEmpty()) Text("Aquí aparece el registro de las sentencias ejecutadas.", Modifier.padding(12.dp), fontSize = 12.sp, color = c.textMuted)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), state = list) {
            items(state.log) { Text(it, style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp), color = if ("ERROR" in it) c.error else c.text) }
        }
        ListScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
