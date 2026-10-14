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

    Box(Modifier.fillMaxSize().background(c.windowBg)) {
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
                    state.saved.forEach { ds -> DropdownMenuItem(text = { Text("${ds.name}  (${ds.kind.label()})", fontSize = 13.sp) }, onClick = { menu = false; state.select(ds); state.connect() }) }
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
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillTab("console [${state.form.name}]", state.activeEditor == CONSOLE_TAB, { state.activeEditor = CONSOLE_TAB }, Modifier.testTag("etab:console"), icon = IconKind.Database)
            state.dataTabs.forEachIndexed { i, t ->
                PillTab(t.obj.name, state.activeEditor == i, { state.activeEditor = i }, Modifier.testTag("etab:$i"), icon = IconKind.Table, onClose = { state.closeDataTab(i) })
            }
        }
        Separator()
        val data = state.dataTabs.getOrNull(state.activeEditor)
        if (data != null) {
            val scope = rememberCoroutineScope()
            Grid(data.grid, onLoadMore = { scope.launch(Dispatchers.IO) { data.grid.loadMore(PAGE) } }, modifier = Modifier.fillMaxSize().testTag("data-grid"))
            return
        }
        Row(Modifier.fillMaxWidth().height(34.dp).background(c.toolbarBg).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Play, "Ejecutar (⌘↵)", state::runSelectionOrCurrent, enabled = !state.running, tint = c.success)
            ToolButton(IconKind.PlayAll, "Ejecutar script (⌘⇧↵)", state::runScript, enabled = !state.running, tint = c.success)
            ToolButton(IconKind.Stop, "Cancelar", state::cancel, enabled = state.running, tint = c.error)
            VSeparator()
            ToolButton(IconKind.Format, "Formatear SQL (⌘⌥L)", state::format)
            VSeparator()
            Text("Tx: Auto", fontSize = 12.sp, color = c.text, modifier = Modifier.padding(horizontal = 6.dp))
            if (state.running) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accentIcon) }
            Spacer(Modifier.weight(1f))
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

@Composable
private fun AppStatusBar(state: AppState) {
    val c = Ksqlc.colors
    StatusBar(Modifier.height(31.dp)) {
        val problem = state.diagnosticAtCaret
        if (problem != null) Text(problem.message, fontSize = 12.sp, color = if (problem.severity == Severity.ERROR) c.error else c.warning, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("problem"))
        else Text(state.status, fontSize = 12.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status"))
        Text("${state.caretLine}:${state.caretColumn}", fontSize = 12.sp, color = c.textMuted, modifier = Modifier.testTag("caret"))
        Text("LF", fontSize = 12.sp, color = c.textMuted)
        Text("UTF-8", fontSize = 12.sp, color = c.textMuted)
        Text("4 spaces", fontSize = 12.sp, color = c.textMuted)
    }
}
