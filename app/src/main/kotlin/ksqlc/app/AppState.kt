package ksqlc.app

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksqlc.core.*
import ksqlc.drivers.DumpScope
import ksqlc.drivers.Introspector
import ksqlc.drivers.JdbcUrl
import ksqlc.drivers.nativeDumpBlocker
import ksqlc.drivers.HostKeyDecider
import ksqlc.drivers.Session
import ksqlc.drivers.Tunnels
import ksqlc.drivers.testConnection
import ksqlc.sql.Diagnostic
import ksqlc.sql.StatementRange
import ksqlc.sql.formatSql
import ksqlc.sql.changesSchema
import ksqlc.sql.simpleSelectTable
import ksqlc.sql.findParams
import ksqlc.sql.firstKeyword
import ksqlc.drivers.explainScript
import ksqlc.sql.fillParams
import ksqlc.sql.splitStatements
import ksqlc.sql.statementAt
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryEntry
import ksqlc.storage.HistoryStore
import ksqlc.storage.Settings
import ksqlc.storage.SettingsStore
import ksqlc.storage.ThemeMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

const val PAGE = 500
private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

/** A live resource tied to the credentials it was opened with; reopened when they change. */
class Holder<T : AutoCloseable>(private val create: (DataSource, String?) -> T) {
    private var value: T? = null
    private var key: List<String?>? = null

    @Synchronized fun get(ds: DataSource, password: String?): T {
        val k = keyOf(ds, password)
        if (value == null || key != k) { value?.close(); value = create(ds, password); key = k }
        return value!!
    }
    @Synchronized fun peek(): T? = value
    @Synchronized fun close() { value?.close(); value = null; key = null }
}

fun keyOf(ds: DataSource, password: String?): List<String?> = listOf(ds.url, ds.user, password, ds.options.toString(), ds.ssh.toString(), ds.sshSecret, ds.ssl.toString(), ds.schemas.toString())

/** A read-only text dialog: a table's DDL, or the SQL a submit would run ([heading] says which). */
class DdlView(val title: String, val text: String, val heading: String = "DDL")

/** A yes/no question shown over the window; [onConfirm] runs when the user agrees. */
private val READ_ONLY_START = setOf("SELECT", "WITH", "VALUES", "TABLE")

class Confirmation(val title: String, val message: String, val confirmLabel: String, val onCancel: () -> Unit = {}, val onConfirm: () -> Unit)

/** Which result the bottom panel shows: the log, or one of the result grids. */
const val OUTPUT_TAB = -1

/** [AppState.activeEditor] value for the console; 0.. selects a data tab. */
const val CONSOLE_TAB = -1

class AppState(
    private val scope: CoroutineScope,
    private val store: ConnectionStore,
    private val settingsStore: SettingsStore = SettingsStore.default(),
    private val history: HistoryStore? = null,
) {
    val form = ConnectionForm()
    var saved by mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList()))
        private set

    init {
        // first start with configured data sources: make the first one the active connection (not connected yet)
        saved.firstOrNull()?.let { c ->
            form.load(c)
            scope.launch {
                form.password = withContext(Dispatchers.IO) { runCatching { store.password(c.id) }.getOrNull() }.orEmpty()
                form.sshSecret = withContext(Dispatchers.IO) { runCatching { store.sshSecret(c.id) }.getOrNull() }.orEmpty()
            }
        }
    }

    // --- window state, persisted -----------------------------------------------------------
    var settings by mutableStateOf(settingsStore.load())
        private set

    fun updateSettings(change: (Settings) -> Settings) {
        settings = change(settings)
        runCatching { settingsStore.save(settings) }
    }

    fun cycleTheme() = updateSettings {
        it.copy(themeMode = when (it.themeMode) { ThemeMode.SYSTEM -> ThemeMode.LIGHT; ThemeMode.LIGHT -> ThemeMode.DARK; ThemeMode.DARK -> ThemeMode.SYSTEM })
    }

    // --- console -----------------------------------------------------------------------
    var editor by mutableStateOf(TextFieldValue("select 1 as one, 'hola' as txt;\nselect 2 as two;"))
        private set
    var caretLine by mutableStateOf(1)
    var caretColumn by mutableStateOf(1)
    /** Problems found in [editor] by the background check; refreshed shortly after each edit. */
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
    val diagnosticAtCaret: Diagnostic? get() = editor.selection.let { s -> if (s.collapsed) diagnostics.firstOrNull { s.start >= it.start && s.start <= it.end } else null }

    /** The text before the last programmatic edit (format, comment, duplicate), so ⌘Z can take it back. */
    private var undoSlot: Pair<String, TextFieldValue>? = null

    /** Replaces the editor content with a result of an editor action, remembering how to undo it. */
    fun applyEdit(v: TextFieldValue) {
        if (v.text != editor.text) { undoSlot = v.text to editor; lastRun = null }
        editor = v
    }

    /** Restores the text from before the last programmatic edit, if the editor still holds exactly what that edit produced. */
    fun undoEdit(): Boolean {
        val (after, before) = undoSlot ?: return false
        if (editor.text != after) return false
        undoSlot = null; editor = before; lastRun = null
        return true
    }

    fun format() {
        val sel = editor.selection
        val whole = sel.collapsed || editor.text.substring(sel.min, sel.max).isBlank()
        val text = editor.text
        if (whole) {
            val out = formatSql(text)
            applyEdit(TextFieldValue(out, TextRange(out.length.coerceAtMost(sel.start.coerceAtMost(out.length)))))
        } else {
            val out = formatSql(text.substring(sel.min, sel.max))
            applyEdit(TextFieldValue(text.replaceRange(sel.min, sel.max, out), TextRange(sel.min, sel.min + out.length)))
        }
        status = "SQL formateado"
    }

    var lastRun by mutableStateOf<RunMarker?>(null)
        private set

    /** Editing invalidates the outline/timing of the statement that last ran, since its position may have moved. */
    fun onEditorChange(v: TextFieldValue) {
        if (v.text != editor.text) lastRun = null
        editor = v
    }

    fun setEditorText(text: String) { editor = TextFieldValue(text, TextRange(text.length)); lastRun = null }

    // --- results -----------------------------------------------------------------------
    val tabs = mutableStateListOf<ResultTab>()
    var selectedTab by mutableStateOf(OUTPUT_TAB)
    val log = mutableStateListOf<String>()
    var lastMillis by mutableStateOf<Long?>(null)
        private set
    var status by mutableStateOf("Listo")
    var running by mutableStateOf(false)
        private set

    /** Table data opened from the explorer; shown as editor tabs next to the console. */
    val dataTabs = mutableStateListOf<DataTab>()
    var activeEditor by mutableStateOf(CONSOLE_TAB)

    fun closeTab(i: Int) {
        val tab = tabs.getOrNull(i) ?: return
        confirmDiscard(tab.edit?.unsaved == true, "«${tab.title}» tiene cambios sin enviar o sin confirmar.") { closeTabNow(i) }
    }

    private fun closeTabNow(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        scope.launch(Dispatchers.IO) { t.dispose() }
        selectedTab = when { tabs.isEmpty() -> OUTPUT_TAB; selectedTab > i -> selectedTab - 1; else -> selectedTab.coerceAtMost(tabs.lastIndex) }
    }

    // --- panels and dialogs ------------------------------------------------------------
    var dataSourceDialog by mutableStateOf(false)
    var ddlView by mutableStateOf<DdlView?>(null)
    var servicesVisible by mutableStateOf(true)

    var explorer by mutableStateOf<TreeModel?>(null)
        private set

    /** What the editor completes and checks against; built after each schema read. */
    var catalog by mutableStateOf<SchemaCatalog?>(null)
        private set

    /** The explorer row that is selected, and a counter that asks it to scroll there. */
    var explorerSelectedId by mutableStateOf<String?>(null)
    var revealTick by mutableStateOf(0)
        private set

    /** Shows [target] in the Database Explorer: opens the panel, expands down to it and selects it. */
    fun revealInExplorer(target: NameTarget) {
        val m = explorer ?: return
        val o = catalog?.objectOf(target.table) ?: return
        if (!settings.explorerVisible) updateSettings { it.copy(explorerVisible = true) }
        scope.launch {
            val id = m.reveal(o, target.column)
            if (id != null) { explorerSelectedId = id; revealTick++ } else status = "${o.name} no está en el explorador"
        }
    }

    /** The "go to table" search is open. */
    var objectSearch by mutableStateOf(false)

    /** Opens the data of [o], or with [reveal] shows it in the explorer; closes the search. */
    fun pickObject(o: DbObject, reveal: Boolean) {
        objectSearch = false
        if (reveal) revealInExplorer(NameTarget(ksqlc.sql.TableRef(o.ns.schema ?: o.ns.catalog, o.name, o.kind != DbObjectKind.TABLE), null)) else openData(o)
    }

    /** ⌘B: jumps from the name at [offset] in the editor to its place in the explorer. */
    fun gotoDeclaration(offset: Int) {
        val cat = catalog ?: run { status = "Sin esquema cargado"; return }
        val t = resolveTarget(editor.text, offset, cat)
        if (t == null) status = "No se encontró la declaración" else revealInExplorer(t)
    }

    /** ⌘⌥B: opens the DDL of the table the name at [offset] refers to. */
    fun showDdlAt(offset: Int) {
        val cat = catalog ?: return
        val t = resolveTarget(editor.text, offset, cat) ?: run { status = "No se encontró la declaración"; return }
        cat.objectOf(t.table)?.let(::showDdl)
    }
    var connecting by mutableStateOf(false)
        private set

    private val sessions = Holder<Session>(::Session)

    /** The console's live connection, or null before the first run and after it was closed for inactivity. */
    internal val consoleSession: Session? get() = sessions.peek()
    private val introspectors = Holder<Introspector>(::Introspector)
    private var explorerKey: List<String?>? = null

    // --- saved connections -------------------------------------------------------------

    /** Makes [c] the active connection (the toolbar selector and the data source dialog use this). */
    fun select(c: DataSource) {
        disconnect()
        form.load(c)
        // The keychain can prompt, so keep it off the UI thread.
        scope.launch {
            form.password = withContext(Dispatchers.IO) { store.password(c.id) }.orEmpty()
            form.sshSecret = withContext(Dispatchers.IO) { store.sshSecret(c.id) }.orEmpty()
        }
    }

    /** Makes [c] the active connection and reads its schema, once its password has been fetched from the keychain. */
    fun selectAndConnect(c: DataSource) {
        confirmDiscard(hasUnsaved, "Hay cambios sin enviar o transacciones sin confirmar.") { switchTo(c) }
    }

    private fun switchTo(c: DataSource) {
        consolePending = false
        disconnect()
        form.load(c)
        scope.launch {
            form.password = withContext(Dispatchers.IO) { store.password(c.id) }.orEmpty()
            form.sshSecret = withContext(Dispatchers.IO) { store.sshSecret(c.id) }.orEmpty()
            connect()
        }
    }

    /** Saves [draft] (new or existing), makes it the active connection and reads its schema. */
    fun applyDraft(draft: ConnectionForm) {
        val id = draft.currentId ?: UUID.randomUUID().toString().also { draft.currentId = it }
        val changed = form.toDataSource().url != draft.toDataSource().url || form.user != draft.user || form.password != draft.password || form.options != draft.options || form.schemas != draft.schemas || form.ssh != draft.ssh || form.sshSecret != draft.sshSecret
        persist({ store.save(draft.toDataSource().copy(id = id), draft.password) }, "Conexión \"${draft.name}\" guardada")
        form.copyFrom(draft)
        if (changed || explorer == null) { disconnect(); connect() }
    }

    fun deleteConnection(id: String) {
        persist({ store.delete(id) }, "Conexión eliminada")
        runCatching { history?.clear(id) }
        if (form.currentId == id) form.currentId = null
    }

    fun storedPassword(id: String): String? = runCatching { store.password(id) }.getOrNull()
    fun storedSshSecret(id: String): String? = runCatching { store.sshSecret(id) }.getOrNull()

    private fun persist(block: () -> Unit, ok: String) {
        runCatching(block)
            .onSuccess { saved = store.list(); status = ok }
            .onFailure { status = "No se pudo guardar: ${it.message}" }
    }

    /** Tries to open [draft]'s connection; [onResult] receives the product name or the error, on the UI thread. */
    fun testConnection(draft: ConnectionForm, onResult: (Result<String>) -> Unit) {
        val ds = draft.toDataSource(); val pw = draft.passwordOrNull
        scope.launch { onResult(runCatching { withContext(Dispatchers.IO) { testConnection(ds, pw) } }) }
    }

    /** Reads every schema [draft]'s server has, to choose from in the Schemas tab; [onResult] gets the names or the error, on the UI thread. */
    fun loadNamespaces(draft: ConnectionForm, onResult: (Result<List<String>>) -> Unit) {
        val ds = draft.toDataSource().copy(schemas = ksqlc.core.SchemaFilter()); val pw = draft.passwordOrNull
        scope.launch {
            onResult(runCatching { withContext(Dispatchers.IO) { Introspector(ds, pw).use { i -> i.allNamespaces().map { it.label } } } })
        }
    }

    // --- running SQL -------------------------------------------------------------------

    /** Oracle scripts keep PL/SQL blocks whole (they end at a `/` line) instead of cutting them at every `;`. */
    /** False for engines without transactions (Cassandra): the Tx controls are hidden. */
    val hasTransactions: Boolean get() = form.effectiveKind != DbKind.CASSANDRA

    /** The SQL dialect the editor's text follows: null = that of the connection. Decides how a script is split and what counts as a parameter. */
    var editorDialect by mutableStateOf<DbKind?>(null)
    private val editorKind: DbKind get() = editorDialect ?: form.effectiveKind
    private val slashBlocks: Boolean get() = editorKind == DbKind.ORACLE
    private val goBatches: Boolean get() = editorKind == DbKind.MSSQL

    // --- notebook ----------------------------------------------------------------------

    /** The console shows each statement of its text as a cell with its result under it. The text stays the single source: cells are its statements. */
    var notebookMode by mutableStateOf(false)

    /** The cell that last had the keyboard; the run buttons act on it. */
    var notebookCell by mutableStateOf(0)

    /** What a cell's last run produced; [source] is the cell text it ran, to tell when the cell has changed since. */
    class CellRun(val source: String, val running: Boolean = false, val tab: ResultTab? = null, val message: String? = null, val ok: Boolean = true, val millis: Long = 0)

    val cellRuns = mutableStateMapOf<Int, CellRun>()

    fun cells(): List<StatementRange> = splitStatements(editor.text, slashBlocks, goBatches)

    private fun cellText(i: Int): String? = cells().getOrNull(i)?.text(editor.text)

    fun editCell(i: Int, text: String) {
        val r = cells().getOrNull(i) ?: return
        setEditorText(editor.text.replaceRange(r.start, r.end, text))
    }

    fun addCell() {
        setEditorText(editor.text.trimEnd().let { if (it.isEmpty()) "select 1;" else (if (it.endsWith(";")) it else "$it;") + "\n\nselect 1;" })
        notebookCell = cells().lastIndex
    }

    fun deleteCell(i: Int) {
        val r = cells().getOrNull(i) ?: return
        setEditorText((editor.text.substring(0, r.start) + editor.text.substring(r.next)).trim().let { if (it.isEmpty()) "" else it })
        val moved = cellRuns.filterKeys { it != i }.mapKeys { (k, _) -> if (k > i) k - 1 else k }
        cellRuns.clear(); cellRuns.putAll(moved)
        notebookCell = notebookCell.coerceAtMost((cells().size - 1).coerceAtLeast(0))
    }

    fun runCell(i: Int) = runCells(listOf(i))

    fun runAllCells() = runCells(cells().indices.toList())

    /** Runs the cells in order, each under its own result, stopping at the first failure. Parameters are asked for once, for all of them. */
    private fun runCells(indexes: List<Int>) {
        if (running) return
        val sources = indexes.mapNotNull { cellText(it) }
        if (sources.isEmpty()) { status = "Nada que ejecutar"; return }
        withParams(sources) { filled ->
            running = true; status = "Ejecutando…"
            val target = form.toDataSource(); val pw = form.passwordOrNull
            scope.launch {
                var failed = false
                for ((n, i) in indexes.withIndex()) {
                    val source = sources[n]
                    cellRuns[i] = CellRun(source, running = true)
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            val r = sessions.get(target, pw).runScript(listOf(filled[n]), PAGE).single()
                            // only the last result of a session keeps its cursor, so a cell keeps the first page it read and lets go of the cursor
                            r to (r as? StatementResult.Rows)?.let { ResultTab("Celda ${i + 1}", it).also { t -> t.loadMore(PAGE); t.close() } }
                        }
                    }
                    outcome.onSuccess { (r, tab) ->
                        cellRuns[i] = when (r) {
                            is StatementResult.Rows -> CellRun(source, tab = tab, millis = r.millis)
                            is StatementResult.Updated -> CellRun(source, message = "${r.count} filas afectadas", millis = r.millis)
                            is StatementResult.Failed -> CellRun(source, message = r.message, ok = false, millis = r.millis)
                        }
                        record(target.id, listOf(r.toHistory(tab)))
                        if (r is StatementResult.Failed) failed = true
                        else if (changesSchema(r.sql) && target.options.autoSync) refreshExplorer()
                    }.onFailure { cellRuns[i] = CellRun(source, message = it.message ?: it.toString(), ok = false); failed = true }
                    if (failed) break
                }
                status = if (failed) "Error en una celda" else "${indexes.size} celda(s) ejecutada(s)"
                running = false
                consolePending = sessions.peek()?.pending == true
                if (explorer == null || explorerKey != keyOf(target, pw)) connect(announce = false)
            }
        }
    }

    fun runSelectionOrCurrent() {
        if (notebookMode) { runCell(notebookCell); return }
        val text = editor.text
        val sel = editor.selection
        if (!sel.collapsed) {
            val part = text.substring(sel.min, sel.max)
            run(splitStatements(part, slashBlocks, goBatches).map { it.text(part) })
        } else {
            val r = statementAt(text, sel.start, slashBlocks, goBatches)
            run(listOfNotNull(r?.text(text)), range = r)
        }
    }

    /**
     * Shows the execution plan of the selected or current statement in a result tab. With [analyze] the statement really runs,
     * so anything but a read asks first.
     */
    fun explain(analyze: Boolean) {
        val text = editor.text
        val sel = editor.selection
        val sql = (if (notebookMode) cellText(notebookCell) else if (!sel.collapsed) text.substring(sel.min, sel.max) else statementAt(text, sel.start, slashBlocks, goBatches)?.text(text))?.trim()?.trimEnd(';')?.trim()
        if (sql.isNullOrBlank()) { status = "Nada que explicar"; return }
        val kind = form.effectiveKind
        val script = explainScript(kind, sql, analyze) ?: run { status = "${if (analyze) "Explain analyze" else "Explain"} no está disponible para ${kind.label()}"; return }
        val go = { run(script.statements, title = "Plan", plan = true, cleanup = script.cleanup) }
        if (analyze && firstKeyword(sql) !in READ_ONLY_START)
            confirmation = Confirmation("Explain analyze", "Esta sentencia se ejecutará de verdad para medirla y puede modificar datos.", "Ejecutar") { confirmation = null; go() }
        else go()
    }

    fun runScript() { if (notebookMode) runAllCells() else runAllText() }

    private fun runAllText() = run(splitStatements(editor.text, slashBlocks, goBatches).map { it.text(editor.text) })

    fun cancel() { sessions.peek()?.cancel() }

    private fun logLine(sql: String, msg: String) {
        log += "[${LocalTime.now().format(clock)}] ${sql.replace(Regex("\\s+"), " ").take(60)} — $msg"
    }

    /**
     * Runs [statements] on the console session. [title] names the result tab (used when opening a table);
     * [range] is where a single statement sits in the editor, so it can be outlined once it has run.
     */
    fun run(statements: List<String>, title: String? = null, range: StatementRange? = null, discardEdits: Boolean = false, filled: Boolean = false,
        plan: Boolean = false, cleanup: List<String> = emptyList(),
    ) {
        if (statements.isEmpty()) { status = "Nada que ejecutar"; return }
        if (running) return
        if (!discardEdits && tabs.any { it.edit?.unsaved == true }) {
            confirmDiscard(true, "Los resultados editados tienen cambios sin enviar o sin confirmar.") { run(statements, title, range, discardEdits = true, filled = filled, plan = plan, cleanup = cleanup) }
            return
        }
        if (!filled) { withParams(statements) { run(it, title, range, discardEdits = true, filled = true, plan = plan, cleanup = cleanup) }; return }
        running = true; status = "Ejecutando…"
        val target = form.toDataSource(); val pw = form.passwordOrNull
        val textAtStart = editor.text
        scope.launch {
            val old = tabs.toList()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    old.forEach { it.dispose() }
                    val session = sessions.get(target, pw)
                    try {
                        session.runScript(statements, PAGE).mapIndexed { i, r ->
                            r to (r as? StatementResult.Rows)?.let { ResultTab(title ?: "Resultado ${i + 1}", it).also { t -> t.plan = plan; t.loadMore(PAGE) } }
                        }
                    } finally {
                        if (cleanup.isNotEmpty()) runCatching { session.runScript(cleanup) }      // e.g. SHOWPLAN off, also after an error
                    }
                }
            }
            outcome.onSuccess { results ->
                tabs.clear(); tabs.addAll(results.mapNotNull { it.second })
                servicesVisible = true      // a run shows its results, even if the panel was hidden
                tabs.forEach { attachEditor(it, target, pw) }
                selectedTab = if (tabs.isEmpty()) OUTPUT_TAB else 0
                results.forEach { (r, tab) ->
                    when (r) {
                        is StatementResult.Rows -> logLine(r.sql, "${tab!!.rows.size} filas${if (tab.exhausted) "" else "+"} en ${r.millis} ms")
                        is StatementResult.Updated -> logLine(r.sql, "${r.count} filas afectadas en ${r.millis} ms")
                        is StatementResult.Failed -> logLine(r.sql, if (r.cancelled) "cancelada" else "ERROR: ${r.message}")
                    }
                }
                record(target.id, results.map { (r, tab) -> r.toHistory(tab) })
                val last = results.lastOrNull()?.first
                lastMillis = last?.millis
                status = when {
                    last is StatementResult.Failed && last.cancelled -> "Cancelada"
                    last is StatementResult.Failed -> "Error en la sentencia ${results.size} de ${statements.size}"
                    else -> "${results.size} sentencia(s) ejecutada(s)"
                }
                if (last is StatementResult.Failed && !last.cancelled) selectedTab = OUTPUT_TAB
                if (range != null && results.size == 1 && last != null && editor.text == textAtStart) {
                    lastRun = RunMarker(range.codeStart(textAtStart), range.end, ok = last !is StatementResult.Failed, millis = last.millis)
                }
                val ddlRan = results.any { (r, _) -> r !is StatementResult.Failed && changesSchema(r.sql) }
                when {
                    explorerKey != keyOf(target, pw) -> connect(announce = false)   // first run, or the connection changed
                    ddlRan && target.options.autoSync -> refreshExplorer()
                }
            }.onFailure { status = "Error: ${it.message}"; logLine("", "ERROR: ${it.message}"); selectedTab = OUTPUT_TAB }
            running = false
            consolePending = sessions.peek()?.pending == true
        }
    }

    // --- schema explorer ---------------------------------------------------------------

    /** Reads the schema. [announce] = false keeps the status line for the caller (used after running SQL). */
    fun connect(announce: Boolean = true) {
        if (connecting) return
        connecting = true
        val ds = form.toDataSource(); val pw = form.passwordOrNull
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { TreeModel(introspectors.get(ds, pw).also { it.includeSystem = settings.showSystemSchemas; it.onlyNamespace = singleDatabaseName(ds); it.schemaFilter = ds.schemas }).also { it.load() } } }
                .onSuccess { explorer = it; explorerKey = keyOf(ds, pw); if (announce) status = "Esquema cargado: ${ds.name}"; loadCatalog(it) }
                .onFailure {
                    explorer = null; explorerKey = null; catalog = null
                    status = "No se pudo leer el esquema: ${it.message}"
                    scope.launch(Dispatchers.IO) { introspectors.close() }
                }
            connecting = false
        }
    }

    private fun loadCatalog(m: TreeModel) {
        val cat = SchemaCatalog(m.source, scope)
        catalog = cat
        scope.launch(Dispatchers.IO) { runCatching { cat.load() } }
    }

    fun disconnect() {
        explorer = null; explorerKey = null; catalog = null
        scope.launch(Dispatchers.IO) { sessions.close(); introspectors.close() }
        status = "Desconectado"
    }

    /** Shows or hides the engine's own schemas in the explorer (and in what the editor completes), and reads the tree again. */
    fun toggleSystemSchemas() {
        updateSettings { it.copy(showSystemSchemas = !it.showSystemSchemas) }
        introspectors.peek()?.includeSystem = settings.showSystemSchemas
        refreshExplorer()
    }

    fun refreshExplorer() {
        val m = explorer ?: return
        scope.launch {
            runCatching { m.refresh() }.onFailure { status = "No se pudo refrescar: ${it.message}" }
            loadCatalog(m)
        }
    }

    /** Opens [o]'s rows in an editor tab beside the console, on a connection of its own so open cursors never clash. */
    fun openData(o: DbObject) {
        val m = explorer ?: return
        dataTabs.indexOfFirst { it.obj == o }.let { if (it >= 0) { activeEditor = it; return } }
        val target = form.toDataSource(); val pw = form.passwordOrNull
        val sql = m.source.dialect.selectAll(o)
        status = "Abriendo ${o.name}…"
        scope.launch {
            val session = Session(target, pw)
            runCatching {
                withContext(Dispatchers.IO) {
                    val key = if (o.kind == DbObjectKind.TABLE) runCatching { m.source.details(o).primaryKey }.getOrDefault(emptyList()) else emptyList()
                    val tab = when (val r = session.runScript(listOf(sql), PAGE).single()) {
                        is StatementResult.Rows -> ResultTab(o.name, r).also { it.loadMore(PAGE) }
                        is StatementResult.Failed -> error(r.message)
                        else -> error("sin resultado")
                    }
                    tab to TableEditor(o, m.source.dialect, target.kind, key, target.options.readOnly)
                }
            }.onSuccess { (tab, editor) ->
                dataTabs += DataTab(o, tab, session, sql, editor); activeEditor = dataTabs.lastIndex
                status = editor.readOnlyReason() ?: "${o.name}: ${tab.rows.size} filas${if (tab.exhausted) "" else "+"}"
            }.onFailure {
                status = "No se pudo abrir ${o.name}: ${it.message}"
                withContext(Dispatchers.IO) { session.close() }
            }
        }
    }

    /**
     * Makes a console result editable when it is a plain read of one table that shows its whole key: opens a connection of its
     * own for sending changes (so the result's open cursor and the console's transaction are left alone). Does nothing otherwise.
     */
    private fun attachEditor(tab: ResultTab, target: DataSource, pw: String?) {
        val m = explorer ?: return
        val cat = catalog ?: return
        val q = simpleSelectTable(tab.result.sql) ?: return
        scope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) {
                    val matches = cat.tables().filter { it.name.equals(q.name, true) && (q.schema == null || it.schema.equals(q.schema, true)) }
                    // an unqualified name found in several schemas depends on the search path: not worth guessing
                    val o = matches.singleOrNull()?.takeIf { !it.isView }?.let(cat::objectOf)?.takeIf { it.kind == DbObjectKind.TABLE } ?: return@withContext null
                    val d = m.source.details(o)
                    val shown = tab.result.columns.map { it.name.lowercase() }
                    val known = d.columns.map { it.name.lowercase() }.toSet()
                    val ok = d.primaryKey.isNotEmpty() && shown.size == shown.toSet().size && shown.all { it in known } && d.primaryKey.all { it.lowercase() in shown }
                    if (!ok) return@withContext null
                    DataTab(o, tab, Session(target, pw), tab.result.sql.trim().trimEnd(';'), TableEditor(o, m.source.dialect, target.kind, d.primaryKey, target.options.readOnly))
                }
            }.getOrNull() ?: return@launch
            if (tab in tabs) tab.edit = prepared else withContext(Dispatchers.IO) { prepared.session.close() }
        }
    }

    /** Re-runs a table tab with the WHERE / ORDER BY currently typed in it. */
    fun applyFilters(tab: DataTab) {
        if (tab.loading) return
        status = "Consultando ${tab.obj.name}…"
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { tab.reload() }.getOrElse { it.message ?: it.toString() } }
            status = if (error != null) "Error: $error" else "${tab.obj.name}: ${tab.grid.rows.size} filas${if (tab.grid.exhausted) "" else "+"}"
        }
    }

    /** Header arrows of a table tab: orders on the server by rewriting its ORDER BY ([asc] null = no order) and reloading. */
    fun sortBy(tab: DataTab, column: Int, asc: Boolean?) {
        if (tab.loading) return
        if (tab.editor.hasChanges) { status = "Envía o revierte los cambios pendientes antes de ordenar"; return }
        val name = tab.grid.result.columns[column].name
        val quote = explorer?.source?.dialect?.let { d -> d::quote } ?: { it }
        tab.orderBy = if (asc == null) "" else "${quote(name)} ${if (asc) "asc" else "desc"}"
        tab.pendingSort = asc?.let { column to it }
        applyFilters(tab)
    }

    /** Sends a tab's pending edits (see [DataTab.submit]) and reports the outcome in the status bar. */
    fun submitChanges(tab: DataTab) {
        if (tab.loading || !tab.editor.hasChanges) return
        status = "Enviando cambios…"
        scope.launch {
            val sent = runCatching { tab.editor.statements(tab.grid) }.getOrDefault(emptyList())
            val error = withContext(Dispatchers.IO) { runCatching { tab.submit() }.getOrElse { it.message ?: it.toString() } }
            val now = System.currentTimeMillis()
            record(form.currentId ?: "unsaved", sent.map { HistoryEntry(now, it.preview.trimEnd(';') + ";", 0, error == null) })
            status = when {
                error != null -> "Error: $error"
                form.manualTx -> "${tab.affected} fila(s) modificadas, pendientes de confirmar (Commit)"
                else -> "${tab.affected} fila(s) modificadas"
            }
        }
    }

    /** Shows the SQL that submitting the pending edits would run. */
    fun previewChanges(tab: DataTab) {
        val text = try { tab.editor.statements(tab.grid).joinToString("\n") { it.preview.trimEnd(';') + ";" } }
        catch (e: ksqlc.drivers.DmlException) { status = "Columna ${e.column}: ${e.message}"; return }
        ddlView = DdlView(tab.obj.name, text.ifBlank { "-- sin cambios pendientes" }, heading = "SQL pendiente")
    }

    fun commitTab(tab: DataTab) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { tab.commit() } }.onSuccess { status = "Commit: ${tab.obj.name}" }.onFailure { status = "Error en el commit: ${it.message}" }
    }

    fun rollbackTab(tab: DataTab) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { tab.rollback() } }.onSuccess { status = "Rollback: ${tab.obj.name}" }.onFailure { status = "Error en el rollback: ${it.message}" }
    }

    // --- transactions -----------------------------------------------------------------

    /** The console's connection holds changes that are not confirmed (manual mode only). */
    var consolePending by mutableStateOf(false)
        private set

    fun commitConsole() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { sessions.peek()?.commit() } }.onSuccess { status = "Commit" }.onFailure { status = "Error en el commit: ${it.message}" }
        consolePending = sessions.peek()?.pending == true
    }

    fun rollbackConsole() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { sessions.peek()?.rollback() } }.onSuccess { status = "Rollback" }.onFailure { status = "Error en el rollback: ${it.message}" }
        consolePending = sessions.peek()?.pending == true
    }

    /** Changes that would be lost by closing everything: pending edits or unconfirmed transactions. */
    val hasUnsaved: Boolean get() = consolePending || dataTabs.any { it.unsaved } || tabs.any { it.edit?.unsaved == true }

    /**
     * Switches between confirming every statement and confirming by hand, for the console and every open table tab, and
     * remembers it with the connection. Not allowed while something is unconfirmed: Commit or Rollback first.
     */
    fun setTxManual(manual: Boolean) {
        if (manual == form.manualTx) return
        if (!manual && hasUnsaved) { status = "Confirma (Commit) o descarta (Rollback) los cambios antes de pasar a Auto"; return }
        form.manualTx = manual
        scope.launch(Dispatchers.IO) { sessions.peek()?.setAutoCommit(!manual); dataTabs.forEach { it.session.setAutoCommit(!manual) }; tabs.forEach { it.edit?.session?.setAutoCommit(!manual) } }
        val id = form.currentId
        if (id != null && saved.any { it.id == id }) {
            val pw = form.password.ifEmpty { storedPassword(id).orEmpty() }
            persist({ store.save(form.toDataSource(), pw) }, "Transacciones: ${if (manual) "manual" else "auto"}")
        } else status = "Transacciones: ${if (manual) "manual" else "auto"}"
    }

    /** Runs [action], first asking for confirmation when [unsaved] work would be thrown away. */
    fun confirmDiscard(unsaved: Boolean, what: String, action: () -> Unit) {
        if (!unsaved) { action(); return }
        confirmation = Confirmation("Cambios sin guardar", "$what Si continúas se descartan.", "Descartar") { confirmation = null; action() }
    }

    var confirmation by mutableStateOf<Confirmation?>(null)

    /** The run waiting for parameter values, if any. */
    var paramPrompt by mutableStateOf<ParamPrompt?>(null)

    /** Last value given to each `:name` parameter, offered again the next time it appears. */
    private val paramMemory = HashMap<String, String>()

    /**
     * Asks for the parameters of [statements] (`:name`, and `?` except in PostgreSQL), then calls [go] with the statements
     * filled in. Without parameters [go] runs at once.
     */
    private fun withParams(statements: List<String>, go: (List<String>) -> Unit) {
        val positional = editorKind != DbKind.POSTGRES
        val refs = statements.map { findParams(it, positional) }
        if (refs.all { it.isEmpty() }) { go(statements); return }
        // one entry per distinct :name, one per `?`, in the order they appear
        var n = 0
        val keys = refs.map { list -> list.map { r -> if (r.name == "?") "? ${++n}" else r.name } }
        val asked = keys.flatten().distinct()
        paramPrompt = ParamPrompt(asked, asked.map { paramMemory[it].orEmpty() }) { values ->
            val byKey = asked.zip(values).toMap()
            asked.zip(values).forEach { (k, v) -> if (!k.startsWith("?")) paramMemory[k] = v }
            go(statements.mapIndexed { i, sql -> fillParams(sql, refs[i], keys[i].map { byKey.getValue(it) }) })
        }
    }

    fun requestDisconnect() = confirmDiscard(hasUnsaved, "Hay cambios sin enviar o transacciones sin confirmar.") { disconnect() }

    /** For the window's close button: [exit] runs at once, or after the user agrees to drop unsaved work. */
    fun requestExit(exit: () -> Unit) = confirmDiscard(hasUnsaved, "Hay cambios sin enviar o transacciones sin confirmar.", exit)

    fun closeDataTab(i: Int) {
        val tab = dataTabs.getOrNull(i) ?: return
        confirmDiscard(tab.unsaved, "«${tab.obj.name}» tiene cambios sin enviar o sin confirmar.") { closeDataTabNow(i) }
    }

    private fun closeDataTabNow(i: Int) {
        if (i !in dataTabs.indices) return
        val t = dataTabs.removeAt(i)
        scope.launch(Dispatchers.IO) { t.close() }
        activeEditor = when { activeEditor == CONSOLE_TAB -> CONSOLE_TAB; activeEditor > i -> activeEditor - 1; activeEditor == i -> (i - 1).coerceAtLeast(CONSOLE_TAB); else -> activeEditor }
    }

    fun showDdl(o: DbObject) {
        val m = explorer ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { m.source.ddl(o) } }
                .onSuccess { ddlView = DdlView(o.name, it) }
                .onFailure { status = "No se pudo obtener el DDL de ${o.name}: ${it.message}" }
        }
    }

    /** Asks to run the alteration [sql] (shown as is), runs it on the console's connection, logs it and reloads the explorer. */
    fun alter(title: String, sql: String) {
        confirmation = Confirmation(title, sql, "Ejecutar") {
            confirmation = null
            val target = form.toDataSource(); val pw = form.passwordOrNull
            status = "Ejecutando…"
            scope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { sessions.get(target, pw).runScript(listOf(sql)).single() } }
                    .getOrElse { StatementResult.Failed(sql, 0, it.message ?: it.toString()) }
                record(target.id, listOf(r.toHistory(null)))
                status = if (r is StatementResult.Failed) "Error: ${r.message}" else "Hecho: $sql"
                refreshExplorer()   // not only on success: a failed batch may still have changed something, and changesSchema misses TRUNCATE and REFRESH
            }
        }
    }

    /** Adds [text] after the current editor content, leaving the caret at the end. */
    fun appendToEditor(text: String) {
        val joined = editor.text.trimEnd().let { if (it.isEmpty()) text else "$it\n\n$text" }
        setEditorText(joined)
    }

    // --- export ------------------------------------------------------------------------
    var exportJob by mutableStateOf<ExportJob?>(null)
        private set

    private fun openExport(r: ExportRequest) { exportJob = ExportJob(r, scope) { status = it; logLine("exportar ${r.name}", it) } }
    fun closeExport() { exportJob?.let { if (it.running) it.cancel() }; exportJob = null }

    /** A console result: the rows already loaded plus whatever is left in its cursor (which then cannot load more). */
    fun exportResult(tab: ResultTab) {
        val dialect = ksqlc.drivers.dialect.Dialect.of(form.effectiveKind)
        val names = tab.result.columns.map { it.name }
        openExport(ExportRequest(
            tab.title, "${form.name} · ${tab.title}", "resultado", dialect::quote, "Las filas que faltan por cargar se leen del cursor; después la pestaña no podrá cargar más.",
            sample = { ExportSample(names, tab.rows.take(PREVIEW_ROWS_EXPORT)) },
        ) { w, stop, progress ->
            try { ksqlc.drivers.exportRows(tab.result, tab.rows.toList(), w(names), stop, progress) }
            finally { tab.exhausted = true }
        })
    }

    /** A table or view: runs [sql] again on its own connection, so the console and open tabs are left alone. */
    fun exportQuery(o: DbObject, sql: String) {
        val target = form.toDataSource(); val pw = form.passwordOrNull
        val dialect = ksqlc.drivers.dialect.Dialect.of(target.kind)
        val db = form.database.substringAfterLast('/').ifBlank { form.name }
        openExport(ExportRequest(
            o.name, listOfNotNull(db, o.ns.label.ifBlank { null }, o.name).joinToString("."), dialect.qualified(o), dialect::quote, null,
            sample = {
                Session(target, pw, autoCommit = true).use { s ->
                    when (val r = s.runScript(listOf(sql), keepRows = 0).single()) {
                        is StatementResult.Rows -> try { ExportSample(r.columns.map { it.name }, r.cursor.fetch(PREVIEW_ROWS_EXPORT)) } finally { r.cursor.close() }
                        is StatementResult.Failed -> error(r.message)
                        else -> error("la consulta no devuelve filas")
                    }
                }
            },
        ) { w, stop, progress ->
            Session(target, pw, autoCommit = true).use { ksqlc.drivers.exportQuery(it, sql, w, stop, progress) }
        })
    }

    fun exportData(tab: DataTab) = exportQuery(tab.obj, tab.query())
    fun exportObject(o: DbObject) { exportQuery(o, explorer?.source?.dialect?.selectAll(o) ?: return) }

    /** Loads a .sql file into the console (run it with "Ejecutar script"). */
    fun openSqlFile() {
        val f = chooseFile(false, "Abrir archivo SQL") ?: return
        runCatching { java.nio.file.Files.readString(java.nio.file.Path.of(f)) }
            .onSuccess { setEditorText(it); activeEditor = CONSOLE_TAB; status = "Abierto $f" }
            .onFailure { status = "No se pudo leer $f: ${it.message}" }
    }

    // --- schema diff -------------------------------------------------------------------
    var diffJob by mutableStateOf<DiffJob?>(null)
        private set

    /** Opens the schema comparison; [ns] and [objectName] say where it was opened from. The left side is the active connection. */
    fun openDiff(ns: Namespace?, objectName: String? = null) {
        val src = explorer?.source ?: run { status = "Conéctate primero"; return }
        val active = form.toDataSource()
        val others = saved.filter { it.id != active.id }
        val sources = listOf(active) + others
        diffJob?.close()
        diffJob = DiffJob(form.name, src, active.id, sources, { storedPassword(it.id) ?: if (it.id == active.id) form.passwordOrNull else null }, scope, ns, objectName)
    }

    fun closeDiff() { diffJob?.close(); diffJob = null }

    // --- native dump / restore ---------------------------------------------------------
    var nativeJob by mutableStateOf<NativeJob?>(null)
        private set

    /** Why dump/restore is not available for the active connection, or null. */
    val nativeBlocker: String? get() = if (form.ssh.enabled) "La conexión usa un túnel SSH: las herramientas de volcado se conectarían directamente" else nativeDumpBlocker(form.effectiveKind, if (form.customUrl) null else JdbcUrl.parse(form.url))

    /** Opens the dump (or restore) dialog. [o] is the table the menu was opened on, [ns] its schema or database. */
    fun openNative(restore: Boolean, o: DbObject? = null, ns: Namespace? = o?.ns) {
        nativeBlocker?.let { status = it; return }
        val kind = form.effectiveKind
        val parts = JdbcUrl.parse(form.url) ?: return
        val schemaName = if (kind == DbKind.MYSQL || kind == DbKind.MARIADB) ns?.catalog else if (kind == DbKind.POSTGRES) ns?.schema else null
        val scopes = buildList {
            if (o != null) add("Tabla ${o.name}" to DumpScope(schemaName, o.name))
            if (schemaName != null) add((if (kind == DbKind.POSTGRES) "Esquema $schemaName" else "Base de datos $schemaName") to DumpScope(schemaName))
            if (kind != DbKind.MYSQL && kind != DbKind.MARIADB || schemaName == null) add((if (kind == DbKind.SQLITE) "Toda la base de datos" else "Base de datos ${parts.database}") to DumpScope())
        }
        nativeJob = NativeJob(restore, kind, parts, form.user.ifBlank { null }, form.passwordOrNull, scopes, settings.toolPaths, scope,
            { name, path -> updateSettings { it.copy(toolPaths = it.toolPaths + (name to path)) } }, { log += "[${LocalTime.now().format(clock)}] $it" })
    }

    fun closeNative() { nativeJob?.let { if (it.running) it.cancel() }; nativeJob = null }

    /** After a restore the schema changed under the explorer. */
    fun nativeFinished(restore: Boolean) {
        if (restore) refreshExplorer()
        status = if (restore) "Restauración terminada" else "Dump terminado"
        closeNative()
    }

    // --- import ------------------------------------------------------------------------
    var importJob by mutableStateOf<ImportJob?>(null)
        private set

    /** Opens the CSV import dialog for table [o]; reads its columns first. */
    fun openImport(o: DbObject) {
        val src = explorer?.source ?: return
        val target = form.toDataSource(); val pw = form.passwordOrNull
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { src.details(o).columns } }
                .onSuccess { importJob = ImportJob(o, it, target, pw, scope) { msg -> status = msg; logLine("importar ${o.name}", msg) } }
                .onFailure { status = "No se pudieron leer las columnas de ${o.name}: ${it.message}" }
        }
    }

    fun closeImport() { importJob = null }

    /** After an import, tabs showing that table pick up the new rows (unless they have unsent edits). */
    fun importFinished(o: DbObject) {
        closeImport()
        dataTabs.firstOrNull { it.obj == o && !it.editor.hasChanges }?.let(::applyFilters)
    }

    // --- history -----------------------------------------------------------------------
    var historyEntries by mutableStateOf<List<HistoryEntry>?>(null)
        private set

    private fun StatementResult.toHistory(tab: ResultTab?) = HistoryEntry(
        System.currentTimeMillis(), sql, millis, ok = this !is StatementResult.Failed,
        rows = when (this) { is StatementResult.Rows -> tab?.rows?.size?.toLong(); is StatementResult.Updated -> count; else -> null },
    )

    private fun record(id: String, entries: List<HistoryEntry>) {
        val h = history ?: return
        scope.launch(Dispatchers.IO) { runCatching { h.append(id, entries) } }
    }

    /** Opens the history of the active connection. */
    fun openHistory() {
        val h = history ?: run { status = "El historial no está disponible"; return }
        val id = form.currentId ?: "unsaved"
        scope.launch { historyEntries = withContext(Dispatchers.IO) { runCatching { h.load(id) }.getOrDefault(emptyList()) } }
    }

    fun closeHistory() { historyEntries = null }

    fun clearHistory() {
        val id = form.currentId ?: "unsaved"
        scope.launch { withContext(Dispatchers.IO) { runCatching { history?.clear(id) } }; historyEntries = emptyList() }
    }

    /** The one namespace "single database" mode keeps: the database or keyspace of the URL, or the user's own schema in Oracle. */
    private fun singleDatabaseName(ds: DataSource): String? = if (!ds.options.singleDatabase) null else when (ds.kind) {
        DbKind.ORACLE -> ds.user
        DbKind.MYSQL, DbKind.MARIADB, DbKind.CASSANDRA -> JdbcUrl.parse(ds.url)?.database
        else -> null
    }?.takeIf { it.isNotBlank() }

    // --- housekeeping: keep-alive, closing an idle console connection, periodic schema reads -----------

    private var lastKeepAlive = System.currentTimeMillis()
    private var lastIntrospection = System.currentTimeMillis()
    /** How long one unit of the introspection interval is; a minute, except in tests. */
    internal var introspectionUnitMillis = 60_000L

    private val housekeeping = scope.launch {
        while (isActive) {
            delay(1000)
            val o = form.options
            val now = System.currentTimeMillis()
            val session = sessions.peek()
            if (o.keepAliveSeconds > 0 && session != null && now - lastKeepAlive >= o.keepAliveSeconds * 1000L) {
                lastKeepAlive = now
                withContext(Dispatchers.IO) { runCatching { session.keepAlive() } }
            }
            if (o.autoDisconnectSeconds > 0 && session != null && !running && !consolePending && !session.busy && now - session.lastUsed >= o.autoDisconnectSeconds * 1000L) {
                withContext(Dispatchers.IO) { sessions.close() }
                status = "Conexión de la consola cerrada por inactividad; se abre de nuevo al ejecutar"
            }
            if (o.introspectionMinutes > 0 && explorer != null && !connecting && now - lastIntrospection >= o.introspectionMinutes * introspectionUnitMillis) {
                lastIntrospection = now
                refreshExplorer()
            }
        }
    }

    /** The SSH host key waiting for the user's answer, if any; asked from the thread that is opening the tunnel. */
    private fun askHostKey(message: String): Boolean {
        val answer = java.util.concurrent.CompletableFuture<Boolean>()
        confirmation = Confirmation(
            "Servidor SSH desconocido", "$message\n\nSi continúas, su clave se recuerda y se avisará si cambia.", "Confiar y continuar",
            onCancel = { answer.complete(false) }, onConfirm = { confirmation = null; answer.complete(true) },
        )
        return runCatching { answer.get(2, java.util.concurrent.TimeUnit.MINUTES) }.getOrDefault(false).also { if (!it) confirmation = null }
    }

    init { Tunnels.hostKeyDecider = HostKeyDecider(::askHostKey) }

    fun dispose() { housekeeping.cancel(); Tunnels.closeAll(); diffJob?.close(); dataTabs.forEach { it.close() }; tabs.forEach { it.dispose() }; sessions.close(); introspectors.close() }
}
