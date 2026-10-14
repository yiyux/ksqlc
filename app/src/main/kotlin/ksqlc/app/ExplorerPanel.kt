@file:OptIn(ExperimentalFoundationApi::class)

package ksqlc.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ksqlc.app.ui.*

private fun iconOf(k: NodeKind) = when (k) {
    NodeKind.NAMESPACE -> IconKind.Schema
    NodeKind.GROUP -> IconKind.Folder
    NodeKind.TABLE -> IconKind.Table
    NodeKind.VIEW -> IconKind.View
    NodeKind.MATERIALIZED_VIEW -> IconKind.MatView
    NodeKind.SEQUENCE -> IconKind.Sequence
    NodeKind.ROUTINE -> IconKind.Routine
    NodeKind.COLUMN -> IconKind.Column
    NodeKind.INDEX -> IconKind.Index
    NodeKind.FOREIGN_KEY -> IconKind.ForeignKey
}

private val Indent = 19.dp

/** One 24dp tree line: chevron, icon, label and a muted detail. [selected] draws the rounded highlight. */
@Composable
private fun TreeLine(
    depth: Int, expandable: Boolean, expanded: Boolean, icon: IconKind, label: String, detail: String?, selected: Boolean,
    loading: Boolean = false, iconTint: androidx.compose.ui.graphics.Color? = null, modifier: Modifier = Modifier,
) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    Row(
        modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(4.dp))
            .background(if (selected) c.selectionInactive else if (hovered) c.hover else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(src).padding(start = Indent * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
            if (expandable) KIcon(if (expanded) IconKind.ChevronDown else IconKind.ChevronRight, tint = c.textSecondary, size = 12.dp)
        }
        Spacer(Modifier.width(3.dp))
        KIcon(icon, tint = iconTint ?: c.textSecondary)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) { Spacer(Modifier.width(8.dp)); Text(detail, fontSize = 12.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        if (loading) { Spacer(Modifier.width(6.dp)); Text("…", fontSize = 12.sp, color = c.textMuted) }
    }
}

@Composable
fun ExplorerPanel(state: AppState, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val model = state.explorer
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var filter by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    val selectedId = state.explorerSelectedId

    Column(modifier) {
        PanelHeader("Database Explorer")
        Separator()
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Plus, "Nuevo data source", { state.dataSourceDialog = true })
            ToolButton(IconKind.Gear, "Propiedades del data source", { state.dataSourceDialog = true })
            VSeparator()
            ToolButton(IconKind.Refresh, "Refrescar", state::refreshExplorer, enabled = model != null)
            ToolButton(IconKind.Filter, "Filtrar", { showFilter = !showFilter; if (!showFilter) filter = "" }, enabled = model != null, selected = showFilter)
            ToolButton(IconKind.Stop, "Desconectar", state::disconnect, enabled = model != null)
        }
        Separator()
        if (showFilter && model != null) {
            CompactTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(8.dp), placeholder = "Filtrar", testTag = "explorer-filter")
        }
        if (model == null && state.saved.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.connecting) "Leyendo el esquema…" else "Sin data sources. Añade uno, o ejecuta SQL en la consola para conectar.",
                    fontSize = 12.sp, color = c.textSecondary,
                )
                if (!state.connecting) {
                    TextAction("Conectar", state::connect)
                    TextAction("Nuevo data source…", { state.dataSourceDialog = true })
                }
            }
            return@Column
        }
        val rows = if (model != null) visibleRows(model.roots, filter) else emptyList()
        val list = rememberLazyListState()
        val activeId = state.form.currentId
        var collapsed by remember(activeId) { mutableStateOf(false) }
        val activeSaved = state.saved.any { it.id == activeId }
        // "go to declaration" from the editor: show everything again and bring the selected row into view
        LaunchedEffect(state.revealTick) {
            if (state.revealTick == 0 || model == null) return@LaunchedEffect
            filter = ""; collapsed = false
            val all = visibleRows(model.roots, "")
            val at = all.indexOfFirst { it.node.id == state.explorerSelectedId }
            if (at >= 0) {
                val rowsBefore = state.saved.takeWhile { it.id != activeId }.size + 1   // connection rows above, plus the active one
                val visible = list.layoutInfo.visibleItemsInfo
                if (visible.isEmpty() || at + rowsBefore < visible.first().index || at + rowsBefore > visible.last().index - 1) list.scrollToItem((at + rowsBefore - 3).coerceAtLeast(0))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp), state = list) {
                for (ds in state.saved) {
                    val active = ds.id == activeId
                    item(key = "ds:${ds.id}") {
                        Column(Modifier.testTag("ds-node:${ds.name}").clickable {
                            when {
                                !active -> { collapsed = false; state.selectAndConnect(ds) }
                                model != null -> collapsed = !collapsed
                                else -> state.connect()
                            }
                        }) {
                            TreeLine(0, expandable = true, expanded = active && model != null && !collapsed, icon = IconKind.Database,
                                label = ds.name, detail = ds.kind.label(), selected = false, loading = active && state.connecting, iconTint = if (active) c.accentIcon else c.textSecondary)
                        }
                    }
                    if (active && model != null && !collapsed) nodeItems(state, model, rows, filter, 1, scope, clipboard)
                }
                if (!activeSaved && model != null) {
                    item(key = "connection-root") {
                        TreeLine(0, expandable = false, expanded = true, icon = IconKind.Database, label = state.form.name, detail = null, selected = false, iconTint = c.accentIcon)
                    }
                    nodeItems(state, model, rows, filter, 1, scope, clipboard)
                }
            }
            ListScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** The tree rows of the connected data source, drawn [base] levels in. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.nodeItems(
    state: AppState, model: TreeModel, rows: List<TreeRow>, filter: String, base: Int,
    scope: kotlinx.coroutines.CoroutineScope, clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    if (rows.isEmpty()) item(key = "empty") {
        val c = Ksqlc.colors
        Text(
            if (filter.isBlank()) "Sin esquemas" else "Nada coincide con «$filter» (solo se filtra lo ya cargado)",
            Modifier.padding(start = 30.dp, top = 6.dp), fontSize = 12.sp, color = c.textMuted,
        )
    }
    items(rows, key = { it.node.id }) { (node, depth) ->
        val c = Ksqlc.colors
        val o = node.obj
        val selectedId = state.explorerSelectedId
        ContextMenuArea(items = {
            buildList {
                if (o != null && o.isReadable && node.kind in setOf(NodeKind.TABLE, NodeKind.VIEW, NodeKind.MATERIALIZED_VIEW)) add(ContextMenuItem("Abrir datos") { state.openData(o) })
                if (o != null) {
                    add(ContextMenuItem("Ver DDL") { state.showDdl(o) })
                    add(ContextMenuItem("Copiar nombre") { clipboard.setText(AnnotatedString(model.source.dialect.qualified(o))) })
                }
                if (node.kind == NodeKind.NAMESPACE) add(ContextMenuItem("Refrescar") { state.refreshExplorer() })
            }
        }) {
            Column(
                Modifier.testTag("tree:${node.id}").combinedClickable(
                    onClick = {
                        state.explorerSelectedId = node.id
                        if (node.expandable) scope.launch { if (node.expanded) node.expanded = false else model.expand(node) }
                    },
                    onDoubleClick = { if (o != null && o.isReadable && node.kind in setOf(NodeKind.TABLE, NodeKind.VIEW, NodeKind.MATERIALIZED_VIEW)) state.openData(o) },
                ),
            ) {
                TreeLine(depth + base, node.expandable, node.expanded, iconOf(node.kind), node.label, node.detail, selectedId == node.id, node.loading)
                node.error?.let {
                    Text("Error: $it", Modifier.padding(start = 36.dp + Indent * (depth + base), bottom = 4.dp), fontSize = 12.sp, color = c.error)
                }
            }
        }
    }
}
