@file:OptIn(ExperimentalFoundationApi::class)

package ksqlc.app

import ksqlc.core.tr
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
import ksqlc.drivers.dropForeignKeySql
import ksqlc.drivers.dropIndexSql
import ksqlc.drivers.dropSql
import ksqlc.drivers.refreshMatViewSql
import ksqlc.drivers.supportsModifyTable
import ksqlc.drivers.supportsRoutineCall
import ksqlc.drivers.truncateSql

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
internal fun TreeLine(
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
fun ExplorerPanel(state: AppState, modifier: Modifier = Modifier, ws: Workspace? = null) {
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
            ToolButton(IconKind.Plus, tr("Nuevo data source"), { state.dataSourceDialog = true })
            ToolButton(IconKind.Gear, tr("Propiedades del data source"), { state.dataSourceDialog = true })
            VSeparator()
            ToolButton(IconKind.Refresh, tr("Refrescar"), state::refreshExplorer, enabled = model != null)
            ToolButton(IconKind.Filter, tr("Filtrar"), { showFilter = !showFilter; if (!showFilter) filter = "" }, enabled = model != null, selected = showFilter)
            ToolButton(IconKind.Schema, tr("Mostrar esquemas de sistema"), state::toggleSystemSchemas, Modifier.testTag("system-schemas"), enabled = model != null, selected = state.settings.showSystemSchemas)
            ToolButton(IconKind.Search, tr("Buscar tabla o vista (⌘⇧O)"), { state.objectSearch = true }, Modifier.testTag("object-search-open"), enabled = model != null)
            ToolButton(IconKind.Stop, tr("Desconectar"), state::requestDisconnect, enabled = model != null)
        }
        Separator()
        if (showFilter && model != null) {
            CompactTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(8.dp), placeholder = tr("Filtrar"), testTag = "explorer-filter")
        }
        if (model == null && state.saved.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.connecting) tr("Leyendo el esquema…") else tr("Sin data sources. Añade uno, o ejecuta SQL en la consola para conectar."),
                    fontSize = 12.sp, color = c.textSecondary,
                )
                if (!state.connecting) {
                    TextAction(tr("Conectar"), state::connect)
                    TextAction(tr("Nuevo data source…"), { state.dataSourceDialog = true })
                }
            }
            return@Column
        }
        val rows = if (model != null) visibleRows(model.roots, filter) else emptyList()
        val list = rememberLazyListState()
        val activeId = state.form.currentId
        var collapsedIds by remember { mutableStateOf(emptySet<String>()) }
        val activeSaved = state.saved.any { it.id == activeId }
        var closedFolders by remember { mutableStateOf(emptySet<String>()) }
        val connRows = connectionRows(state.saved, closedFolders)
        // "go to declaration" from the editor: show everything again and bring the selected row into view
        LaunchedEffect(state.revealTick) {
            if (state.revealTick == 0 || model == null) return@LaunchedEffect
            filter = ""; collapsedIds = emptySet()
            val all = visibleRows(model.roots, "")
            val at = all.indexOfFirst { it.node.id == state.explorerSelectedId }
            if (at >= 0) {
                val rowsBefore = connRows.takeWhile { !(it is ConnectionRow.Item && it.ds.id == activeId) }.size + 1   // connection and folder rows above, plus the active one
                val visible = list.layoutInfo.visibleItemsInfo
                if (visible.isEmpty() || at + rowsBefore < visible.first().index || at + rowsBefore > visible.last().index - 1) list.scrollToItem((at + rowsBefore - 3).coerceAtLeast(0))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp), state = list) {
                for (r in connRows) when (r) {
                    is ConnectionRow.Folder -> item(key = "folder:${r.name}") {
                        Column(Modifier.testTag("folder:${r.name}").clickable { closedFolders = if (r.open) closedFolders + r.name else closedFolders - r.name }) {
                            TreeLine(0, expandable = true, expanded = r.open, icon = IconKind.Folder, label = r.name, detail = "(${r.count})", selected = false)
                        }
                    }
                    is ConnectionRow.Item -> {
                        val ds = r.ds
                        val active = ds.id == activeId
                        val depth = if (r.inFolder) 1 else 0
                        // every console with a loaded schema shows its own tree; the active one wins when several share a data source
                        val k = (if (active && model != null) state else null) ?: ws?.consoles?.firstOrNull { it.form.currentId == ds.id && it.explorer != null } ?: if (active) state else null
                        val m = k?.explorer
                        val open = m != null && ds.id !in collapsedIds
                        item(key = "ds:${ds.id}") {
                            ContextMenuArea(items = { if (ws == null) emptyList() else listOf(ContextMenuItem(tr("Nueva consola SQL")) { ws.newConsole(ds) }) }) {
                            Column(Modifier.testTag("ds-node:${ds.name}").clickable {
                                when {
                                    m == null && !active -> { collapsedIds -= ds.id; ws?.open(ds) ?: state.selectAndConnect(ds) }
                                    m == null -> state.connect()
                                    else -> { collapsedIds = if (ds.id in collapsedIds) collapsedIds - ds.id else collapsedIds + ds.id; ws?.activate(ws.consoles.indexOf(k)) }
                                }
                            }) {
                                TreeLine(depth, expandable = true, expanded = open, icon = IconKind.Database,
                                    label = ds.name, detail = ds.kind.label(), selected = false, loading = k?.connecting == true, iconTint = connectionTint(ds.color, active || m != null))
                            }
                            }
                        }
                        if (open && m != null) nodeItems(k, m, visibleRows(m.roots, filter), filter, depth + 1, scope, clipboard) { ws?.activate(ws.consoles.indexOf(k)) }
                    }
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
    activate: () -> Unit = {},
) {
    val owner = state.form.currentId
    if (rows.isEmpty()) item(key = "empty:$owner") {
        val c = Ksqlc.colors
        Text(
            if (filter.isBlank()) tr("Sin esquemas") else tr("Nada coincide con «%s» (solo se filtra lo ya cargado)", filter),
            Modifier.padding(start = 30.dp, top = 6.dp), fontSize = 12.sp, color = c.textMuted,
        )
    }
    items(rows, key = { "$owner:${it.node.id}" }) { (node, depth) ->
        fun ContextMenuItem(label: String, action: () -> Unit) = androidx.compose.foundation.ContextMenuItem(label) { activate(); action() }
        val c = Ksqlc.colors
        val o = node.obj
        val selectedId = state.explorerSelectedId
        ContextMenuArea(items = {
            buildList {
                if (o != null && o.isReadable && node.kind in setOf(NodeKind.TABLE, NodeKind.VIEW, NodeKind.MATERIALIZED_VIEW)) add(ContextMenuItem(tr("Abrir datos")) { state.openData(o) })
                if (o != null) {
                    if (o.isReadable && node.kind in setOf(NodeKind.TABLE, NodeKind.VIEW, NodeKind.MATERIALIZED_VIEW)) add(ContextMenuItem(tr("Exportar datos…")) { state.exportObject(o) })
                    if (node.kind == NodeKind.TABLE) add(ContextMenuItem(tr("Importar CSV…")) { state.openImport(o) })
                    if (node.kind == NodeKind.TABLE) add(ContextMenuItem(tr("Dump con la herramienta del motor…")) { state.openNative(false, o) })
                    if (node.kind == NodeKind.ROUTINE && supportsRoutineCall(state.form.effectiveKind)) add(ContextMenuItem(tr("Ejecutar…")) { state.callRoutine(o) })
                    if (node.kind == NodeKind.TABLE && supportsModifyTable(state.form.effectiveKind) && !state.form.options.readOnly) add(ContextMenuItem(tr("Modificar…")) { state.openModify(o) })
                    add(ContextMenuItem(tr("Comparar esquema con…")) { state.openDiff(o.ns, o.name) })
                    if (node.kind == NodeKind.TABLE) add(ContextMenuItem(tr("Comparar contenido con…")) { state.openContentDiff(o) })
                    if (node.kind == NodeKind.TABLE) add(ContextMenuItem(tr("Copiar tabla a…")) { state.openCopyTable(o) })
                    add(ContextMenuItem(tr("Ver DDL")) { state.showDdl(o) })
                    add(ContextMenuItem(tr("Copiar nombre")) { clipboard.setText(AnnotatedString(model.source.dialect.qualified(o))) })
                    if (!state.form.options.readOnly) {
                        val k = state.form.effectiveKind
                        when (node.kind) {
                            NodeKind.INDEX -> node.dbName?.let { n -> add(ContextMenuItem(tr("Eliminar índice…")) { state.alter(tr("Eliminar el índice %s", n), dropIndexSql(k, o, n)) }) }
                            NodeKind.FOREIGN_KEY -> node.dbName?.let { n -> dropForeignKeySql(k, o, n)?.let { sql -> add(ContextMenuItem(tr("Eliminar clave foránea…")) { state.alter(tr("Eliminar la clave foránea %s", n), sql) }) } }
                            NodeKind.COLUMN -> {}
                            else -> {
                                truncateSql(k, o)?.let { sql -> add(ContextMenuItem(tr("Vaciar tabla…")) { state.alter(tr("Vaciar %s", o.name), sql) }) }
                                refreshMatViewSql(k, o)?.let { sql -> add(ContextMenuItem(tr("Refrescar vista materializada")) { state.alter(tr("Refrescar %s", o.name), sql) }) }
                                dropSql(k, o)?.let { sql -> add(ContextMenuItem(tr("Eliminar…")) { state.alter(tr("Eliminar %s", o.name), sql) }) }
                            }
                        }
                    }
                }
                if (node.kind == NodeKind.NAMESPACE) {
                    add(ContextMenuItem(tr("Dump con la herramienta del motor…")) { state.openNative(false, null, node.ns) })
                    add(ContextMenuItem(tr("Restaurar con la herramienta del motor…")) { state.openNative(true, null, node.ns) })
                    add(ContextMenuItem(tr("Comparar con…")) { state.openDiff(node.ns) })
                    add(ContextMenuItem(tr("Refrescar")) { state.refreshExplorer() })
                }
            }
        }) {
            Column(
                Modifier.testTag("tree:${node.id}").combinedClickable(
                    onClick = {
                        activate()
                        state.explorerSelectedId = node.id
                        if (node.expandable) scope.launch { if (node.expanded) node.expanded = false else model.expand(node) }
                    },
                    onDoubleClick = { activate(); if (o != null && o.isReadable && node.kind in setOf(NodeKind.TABLE, NodeKind.VIEW, NodeKind.MATERIALIZED_VIEW)) state.openData(o) },
                ),
            ) {
                TreeLine(depth + base, node.expandable, node.expanded, iconOf(node.kind), node.label, node.detail, selectedId == node.id, node.loading)
                node.error?.let {
                    Text(tr("Error: %s", it), Modifier.padding(start = 36.dp + Indent * (depth + base), bottom = 4.dp), fontSize = 12.sp, color = c.error)
                }
            }
        }
    }
}
