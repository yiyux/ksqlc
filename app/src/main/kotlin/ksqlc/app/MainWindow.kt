package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import ksqlc.sql.TokenKind
import ksqlc.sql.tokenize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.sql.Severity
import ksqlc.sql.diagnose
import ksqlc.app.ui.*
import ksqlc.storage.ThemeMode

@Composable
fun App(state: AppState) = KsqlcTheme(state.settings.themeMode) { MainWindow(state) }

/** Islands on the window background: toolbar on top, Database Explorer left, editor over Services, status bar below. */
@Composable
fun MainWindow(state: AppState) {
    val c = Ksqlc.colors
    val s = state.settings
    var explorerWidth by remember { mutableStateOf(s.explorerWidthDp.dp) }
    var resultsRatio by remember { mutableStateOf(s.resultsRatio) }

    Box(Modifier.fillMaxSize().background(c.windowBg).onPreviewKeyEvent { e ->
        // ⌥⌘E opens the history from anywhere
        if (e.type == KeyEventType.KeyDown && e.key == Key.E && e.isMetaPressed && e.isAltPressed) { state.openHistory(); true }
        // ⌘⇧O searches tables and views by name
        else if (e.type == KeyEventType.KeyDown && e.key == Key.O && e.isMetaPressed && e.isShiftPressed) { state.objectSearch = true; true }
        // ⌘8 shows or hides Services
        else if (e.type == KeyEventType.KeyDown && e.key == Key.Eight && e.isMetaPressed) { state.servicesVisible = !state.servicesVisible; true }
        else false
    }) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state)
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 7.dp, end = 7.dp)) {
                if (s.explorerVisible) {
                    Island(Modifier.width(explorerWidth).fillMaxHeight().testTag("island:explorer")) { ExplorerPanel(state, Modifier.fillMaxSize()) }
                    Splitter(true, { d -> explorerWidth = (explorerWidth + d).coerceIn(200.dp, 700.dp) }, onDragEnd = { state.updateSettings { it.copy(explorerWidthDp = explorerWidth.value.toInt()) } })
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val total = maxHeight
                    Column(Modifier.fillMaxSize()) {
                        Island(Modifier.fillMaxWidth().weight(if (state.servicesVisible) 1f - resultsRatio else 1f).testTag("island:editor")) { EditorIsland(state) }
                        if (state.servicesVisible) {
                            Splitter(false, { d -> resultsRatio = (resultsRatio - d / total).coerceIn(0.15f, 0.8f) }, onDragEnd = { state.updateSettings { it.copy(resultsRatio = resultsRatio) } })
                            Island(Modifier.fillMaxWidth().weight(resultsRatio).testTag("island:services")) { ServicesPanel(state, Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            AppStatusBar(state)
        }
        if (state.dataSourceDialog) DataSourceDialog(state)
        DdlDialog(state)
        HistoryDialog(state)
        ExportDialog(state)
        ImportDialog(state)
        NativeDialog(state)
        DiffDialog(state)
        ConfirmDialog(state)
        ParamDialog(state)
        ObjectSearchDialog(state)
    }
}

@Composable
private fun TopBar(state: AppState) {
    val c = Ksqlc.colors
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 12.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ksqlc", fontSize = 13.sp, color = c.textSecondary)
            Box {
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(horizontal = 8.dp).testTag("connection-selector"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KIcon(IconKind.Database, tint = c.accentIcon)
                    Text(state.form.name, fontSize = 13.sp, color = c.text, maxLines = 1)
                    KIcon(IconKind.ChevronDown, tint = c.textSecondary, size = 12.dp)
                }
                DropdownMenu(menu, { menu = false }) {
                    state.saved.forEach { ds -> DropdownMenuItem(text = { Text("${ds.name}  (${ds.kind.label()})", fontSize = 13.sp) }, onClick = { menu = false; state.selectAndConnect(ds) }) }
                    DropdownMenuItem(text = { Text("Nuevo data source…", fontSize = 13.sp) }, onClick = { menu = false; state.dataSourceDialog = true })
                }
            }
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton(IconKind.Database, "Database Explorer", { state.updateSettings { it.copy(explorerVisible = !it.explorerVisible) } }, selected = state.settings.explorerVisible)
            ToolButton(IconKind.Play, "Services", { state.servicesVisible = !state.servicesVisible }, selected = state.servicesVisible)
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton(IconKind.Contrast, "Tema: ${themeLabel(state.settings.themeMode)}", state::cycleTheme)
            ToolButton(IconKind.Gear, "Data sources", { state.dataSourceDialog = true })
        }
    }
}

private fun themeLabel(m: ThemeMode) = when (m) { ThemeMode.SYSTEM -> "sistema"; ThemeMode.LIGHT -> "claro"; ThemeMode.DARK -> "oscuro" }

@Composable
private fun EditorIsland(state: AppState) {
    val c = Ksqlc.colors
    Column(Modifier.fillMaxSize()) {
        OverflowTabs(
            buildList {
                add(TabSpec("console [${state.form.name}]", state.activeEditor == CONSOLE_TAB, "etab:console", IconKind.Database, { state.activeEditor = CONSOLE_TAB }))
                state.dataTabs.forEachIndexed { i, t -> add(TabSpec(t.obj.name, state.activeEditor == i, "etab:$i", IconKind.Table, { state.activeEditor = i }, { state.closeDataTab(i) })) }
            },
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp),
        )
        Separator()
        val data = state.dataTabs.getOrNull(state.activeEditor)
        if (data != null) {
            DataTabContent(state, data)
            return
        }
        Row(Modifier.fillMaxWidth().height(34.dp).background(c.toolbarBg).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Play, "Ejecutar (⌘↵)", state::runSelectionOrCurrent, enabled = !state.running, tint = c.success)
            ToolButton(IconKind.PlayAll, "Ejecutar script (⌘⇧↵)", state::runScript, enabled = !state.running, tint = c.success)
            ToolButton(IconKind.Stop, "Cancelar", state::cancel, enabled = state.running, tint = c.error)
            ToolButton(IconKind.Explain, "Explain: plan de ejecución", { state.explain(false) }, Modifier.testTag("explain"), enabled = !state.running)
            ToolButton(IconKind.Analyze, "Explain analyze: ejecuta y mide", { state.explain(true) }, Modifier.testTag("explain-analyze"), enabled = !state.running)
            VSeparator()
            ToolButton(IconKind.Format, "Formatear SQL (⌘⌥L)", state::format)
            ToolButton(IconKind.Folder, "Abrir archivo SQL…", state::openSqlFile)
            ToolButton(IconKind.History, "Historial (⌥⌘E)", state::openHistory, Modifier.testTag("history-open"))
            VSeparator()
            if (state.hasTransactions) TxControls(state.form.manualTx, state.consolePending, state::setTxManual, state::commitConsole, state::rollbackConsole, "console")
            if (state.running) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accentIcon) }
            Spacer(Modifier.weight(1f))
            DialectMenu(state)
            KIcon(IconKind.Database, tint = c.textSecondary, size = 14.dp)
            Text(state.form.name, fontSize = 12.sp, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
        }
        Separator()
        val catalog = state.catalog
        val version = catalog?.version ?: 0
        // problems are checked a moment after typing stops, off the UI thread
        LaunchedEffect(state.editor.text, catalog, version) {
            delay(300)
            val text = state.editor.text
            state.diagnostics = withContext(Dispatchers.Default) { diagnose(text, catalog) }
        }
        val quote: (String) -> String = { name -> state.explorer?.source?.dialect?.quote(name) ?: "\"$name\"" }
        val support = remember(catalog, version, state.diagnostics) {
            EditorSupport(
                schema = catalog, schemaVersion = version, diagnostics = state.diagnostics, quote = quote,
                onEdit = state::applyEdit, onUndo = state::undoEdit, onGoto = state::gotoDeclaration, onShowDdl = state::showDdlAt, onFormat = state::format,
            )
        }
        SqlEditor(
            state.editor, state::onEditorChange, state.lastRun, state::runSelectionOrCurrent, state::runScript,
            { l, col -> state.caretLine = l; state.caretColumn = col },
            Modifier.fillMaxWidth().weight(1f).testTag("editor"),
            support,
        )
    }
}

/** Which SQL dialect the console text follows: the connection's (automatic) or any engine's. */
@Composable
private fun DialectMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        DropdownField("Dialecto: " + (state.editorDialect?.label() ?: "auto"), { open = true }, Modifier.width(190.dp).testTag("dialect"))
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Automático (el de la conexión)", fontSize = 13.sp) }, onClick = { open = false; state.editorDialect = null }, modifier = Modifier.testTag("dialect:auto"))
            ksqlc.core.DbKind.entries.forEach { k ->
                DropdownMenuItem(text = { Text(k.label(), fontSize = 13.sp) }, onClick = { open = false; state.editorDialect = k }, modifier = Modifier.testTag("dialect:${k.name}"))
            }
        }
    }
}

@Composable
private fun AppStatusBar(state: AppState) {
    val c = Ksqlc.colors
    StatusBar(Modifier.height(31.dp)) {
        // the way back to Services once its panel has been closed
        Row(
            Modifier.height(22.dp).clip(RoundedCornerShape(5.dp)).background(if (state.servicesVisible) c.selectionInactive else androidx.compose.ui.graphics.Color.Transparent).clickable { state.servicesVisible = !state.servicesVisible }
                .padding(horizontal = 6.dp).testTag("services-toggle"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KIcon(IconKind.Play, tint = c.textSecondary, size = 12.dp)
            Text("Services", fontSize = 12.sp, color = c.textSecondary)
        }
        val problem = state.diagnosticAtCaret
        if (problem != null) Text(problem.message, fontSize = 12.sp, color = if (problem.severity == Severity.ERROR) c.error else c.warning, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("problem"))
        else Text(state.status, fontSize = 12.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status"))
        Text("${state.caretLine}:${state.caretColumn}", fontSize = 12.sp, color = c.textMuted, modifier = Modifier.testTag("caret"))
        Text("LF", fontSize = 12.sp, color = c.textMuted)
        Text("UTF-8", fontSize = 12.sp, color = c.textMuted)
        Text("4 spaces", fontSize = 12.sp, color = c.textMuted)
    }
}

/** A table's rows under a small toolbar and the WHERE / ORDER BY boxes; Enter in either re-runs the query. */
@Composable
private fun DataTabContent(state: AppState, data: DataTab) {
    val c = Ksqlc.colors
    val scope = rememberCoroutineScope()
    val grid = data.grid
    val columns = remember(grid) { grid.result.columns.map { it.name.lowercase() }.toSet() }
    Column(Modifier.fillMaxSize()) {
        val ed = data.editor
        Row(Modifier.fillMaxWidth().height(34.dp).background(c.toolbarBg).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Refresh, "Recargar", { if (ed.hasChanges) state.status = "Envía o revierte los cambios pendientes antes de recargar" else state.applyFilters(data) }, enabled = !data.loading)
            VSeparator()
            EditButtons(state, data)
            ToolButton(IconKind.Export, "Exportar datos…", { state.exportData(data) }, Modifier.testTag("export-open"), enabled = !data.loading)
            VSeparator()
            EditStatus(state, data, "tab")
        }
        Separator()
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterBox(IconKind.Filter, "WHERE", data.where, columns, { data.where = it }, { state.applyFilters(data) }, "filter-where", Modifier.weight(1f))
            VSeparator()
            FilterBox(IconKind.Sort, "ORDER BY", data.orderBy, columns, { data.orderBy = it }, { state.applyFilters(data) }, "filter-order", Modifier.weight(1f))
        }
        Separator()
        Grid(grid, onLoadMore = { scope.launch(Dispatchers.IO) { grid.loadMore(PAGE) } }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("data-grid"), editing = ed, onNotice = { state.status = it }, onSort = { j, asc -> state.sortBy(data, j, asc) })
    }
}

/** Add / delete / revert / preview / send for the changes of [data]; shared by table tabs and editable console results. */
@Composable
internal fun RowScope.EditButtons(state: AppState, data: DataTab) {
    val c = Ksqlc.colors
    val ed = data.editor
    ToolButton(IconKind.Plus, "Añadir fila", { ed.addRow(data.grid.result.columns.size) }, enabled = ed.canInsert && !data.loading)
    ToolButton(IconKind.Minus, "Borrar filas seleccionadas", { ed.deleteSelected(data.grid) }, enabled = ed.canInsert && !data.loading)
    ToolButton(IconKind.Revert, "Revertir cambios", ed::revert, enabled = ed.hasChanges && !data.loading)
    ToolButton(IconKind.Preview, "Ver SQL de los cambios", { state.previewChanges(data) }, enabled = ed.hasChanges)
    ToolButton(IconKind.Submit, "Enviar cambios", { state.submitChanges(data) }, enabled = ed.hasChanges && !data.loading, tint = if (ed.hasChanges) c.success else null)
}

/** Transaction controls, the count of unsent changes and the loading spinner of [data]; [scope] keeps test tags apart. */
@Composable
internal fun RowScope.EditStatus(state: AppState, data: DataTab, scope: String) {
    val c = Ksqlc.colors
    if (state.hasTransactions) TxControls(state.form.manualTx, data.pending, state::setTxManual, { state.commitTab(data) }, { state.rollbackTab(data) }, scope)
    if (data.editor.hasChanges) Text("${data.editor.changeCount} cambio(s) sin enviar", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(start = 8.dp).testTag(if (scope == "tab") "pending-count" else "pending-count:$scope"))
    if (data.loading) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accentIcon) }
}

/** SQL colours for a filter box; names that are not columns of the table (and not calls) are drawn as errors. */
fun highlightFilter(text: String, c: KsqlcColors, columns: Set<String>): AnnotatedString {
    val base = highlight(text, c)
    val toks = tokenize(text)
    return buildAnnotatedString {
        append(base)
        for ((i, t) in toks.withIndex()) {
            if (t.kind != TokenKind.IDENT) continue
            val name = t.text(text)
            val next = toks.drop(i + 1).firstOrNull { it.kind != TokenKind.WHITESPACE }
            val prev = toks.take(i).lastOrNull { it.kind != TokenKind.WHITESPACE }
            if (next != null && text[next.start] == '(' || prev != null && text[prev.start] == '.' || name.lowercase() in columns || name.uppercase().startsWith("CURRENT_")) continue
            addStyle(SpanStyle(color = c.error), t.start, t.end)
        }
    }
}

/** One filter box: icon, the clause keyword (a muted hint while empty, keyword-coloured once there is text), the highlighted input, and ✕ to clear. */
@Composable
private fun FilterBox(icon: IconKind, keyword: String, value: String, columns: Set<String>, onChange: (String) -> Unit, onApply: () -> Unit, tag: String, modifier: Modifier) {
    val c = Ksqlc.colors
    val transformation = remember(c, columns) { VisualTransformation { t -> TransformedText(highlightFilter(t.text, c, columns), OffsetMapping.Identity) } }
    Row(modifier.fillMaxHeight().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KIcon(icon, tint = c.textSecondary, size = 14.dp)
        Text(keyword, style = MonoStyle.copy(fontSize = 13.sp), color = if (value.isEmpty()) c.textMuted else c.synKeyword, maxLines = 1)
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, Modifier.weight(1f).testTag(tag).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onApply(); true } else false
            },
            singleLine = true, textStyle = MonoStyle.copy(color = c.synIdent, fontSize = 13.sp), cursorBrush = SolidColor(c.text), visualTransformation = transformation,
        )
        if (value.isNotEmpty()) ToolButton(IconKind.Close, "Borrar $keyword", { onChange(""); onApply() }, modifier = Modifier.testTag("$tag-clear"))
    }
}

/**
 * `Tx: Auto ▾` / `Tx: Manual ▾` menu and, in manual mode, Commit and Rollback buttons (enabled while [pending]).
 * [scope] only keeps the test tags of the console's and the table tabs' copies apart.
 */
@Composable
private fun TxControls(manual: Boolean, pending: Boolean, onMode: (Boolean) -> Unit, onCommit: () -> Unit, onRollback: () -> Unit, scope: String) {
    val c = Ksqlc.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).clickable { menu = true }.padding(horizontal = 6.dp).testTag("tx-mode:$scope"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(if (manual) "Tx: Manual" else "Tx: Auto", fontSize = 12.sp, color = c.text)
            if (manual && pending) Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.warning).testTag("tx-pending:$scope"))
            KIcon(IconKind.ChevronDown, tint = c.textSecondary, size = 10.dp)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Auto", fontSize = 13.sp) }, onClick = { menu = false; onMode(false) }, modifier = Modifier.testTag("tx-auto:$scope"))
            DropdownMenuItem(text = { Text("Manual", fontSize = 13.sp) }, onClick = { menu = false; onMode(true) }, modifier = Modifier.testTag("tx-manual:$scope"))
        }
    }
    if (manual) {
        ToolButton(IconKind.Check, "Commit", onCommit, Modifier.testTag("commit:$scope"), enabled = pending, tint = if (pending) c.success else null)
        ToolButton(IconKind.Rollback, "Rollback", onRollback, Modifier.testTag("rollback:$scope"), enabled = pending, tint = if (pending) c.error else null)
    }
}
