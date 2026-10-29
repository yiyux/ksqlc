package ksqlc.app

import ksqlc.core.tr
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import ksqlc.drivers.CellValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*

private val RowNumberWidth = 39.dp
private val ColumnWidth = 112.dp
private const val MAX_LOAD_BYTES = 64_000_000L

/** Width of a column: dates and times get room for their full text (`2026-01-31 10:00:00`, plus `+01:00` with a zone) in the monospace font. */
internal fun columnWidth(typeName: String, engine: ksqlc.core.DbKind?) = when (temporalKind(typeName, engine)) {
    TemporalKind.DATETIME -> 168.dp
    TemporalKind.DATETIME_TZ -> 216.dp
    else -> ColumnWidth
}
private val HeaderChrome = 70.dp      // header padding + column icon + funnel + sort arrow + gaps
private val MaxHeaderWidth = 400.dp
private val MaxFitWidth = 600.dp
private val CellPadding = 13.dp        // 6dp each side + the grid line
private val MinColumnWidth = 40.dp
private val HeaderHeight = 26.dp
private val RowHeight = 27.dp

/** Display text of a cell: SQL NULL as `<null>`, line breaks as ⏎ so every row stays one line high. */
fun cellText(v: Any?): String = v?.toString()?.replace("\r\n", "⏎")?.replace('\n', '⏎')?.replace('\r', '⏎') ?: "<null>"

/**
 * Result table: row-number column, 26dp header, 27dp rows with 1dp grid lines, 112dp columns, numbers right-aligned.
 * Click selects a cell (⌘C / Ctrl+C copies its value); the "more rows" pill floats at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Grid(tab: ResultTab, onLoadMore: () -> Unit, modifier: Modifier = Modifier, editing: TableEditor? = null, onNotice: (String) -> Unit = {},
         /** Header arrows. Null = the grid orders the rows it has loaded; given = the owner orders them (e.g. in the query) and reloads. */
         onSort: ((column: Int, asc: Boolean?) -> Unit)? = null,
         /** The floating "N rows" pill; off where the owner says the count itself (a notebook cell). */
         showRowsPill: Boolean = true) {
    val c = Ksqlc.colors
    val clipboard = LocalClipboardManager.current
    val hscroll = rememberScrollState()
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var selected by remember(tab) { mutableStateOf<Pair<Int, Int>?>(null) }
    var editCell by remember(tab) { mutableStateOf<Pair<Int, Int>?>(null) }
    var bigCell by remember(tab) { mutableStateOf<Pair<Int, Int>?>(null) }
    var draft by remember(tab) { mutableStateOf(TextFieldValue("")) }
    var anchorRow by remember(tab) { mutableStateOf(-1) }
    val cols = tab.result.columns
    val newRows = editing?.inserts?.size ?: 0
    val total = tab.rows.size + newRows
    // rows the local column filters let through (rows being added are always shown); `i` below is always the real row index
    val filtersSnapshot = tab.filters.toMap()
    val sort = tab.sort
    val visible = remember(tab.rows.size, filtersSnapshot, newRows, sort, onSort == null) {
        val shown = if (filtersSnapshot.isEmpty()) List(total) { it } else (0 until total).filter { it >= tab.rows.size || tab.passes(tab.rows[it]) }
        if (sort == null || onSort != null) shown
        else {
            val (col, asc) = sort
            val (old, added) = shown.partition { it < tab.rows.size }
            old.sortedWith { a, b -> compareCells(tab.rows[a].getOrNull(col), tab.rows[b].getOrNull(col)).let { if (asc) it else -it } } + added
        }
    }
    var filterOpen by remember(tab) { mutableStateOf<Int?>(null) }
    // a column counts as numeric (right-aligned) when its first non-null value is a number
    val numeric = remember(tab, cols) { cols.indices.map { j -> tab.rows.firstNotNullOfOrNull { it.getOrNull(j) } is Number } }

    /** What the grid shows in a cell and how: the pending value if any, else what was read. */
    fun textOf(i: Int, j: Int): String? {                       // null = SQL NULL
        return when (val v = editing?.valueAt(i, j, tab)) {
            is CellValue.Text -> v.text
            is CellValue.Null -> null
            is CellValue.Default -> null
            is CellValue.Bytes -> "<${v.name}: ${v.data.size} bytes>"
            null -> tab.rows.getOrNull(i)?.getOrNull(j)?.let { if (it is ByteArray) "<${it.size} bytes>" else it.toString() }
        }
    }
    /** The bytes a binary cell holds now: what was loaded from a file, else what the database returned. */
    fun bytesOf(i: Int, j: Int): ByteArray? = when (val v = editing?.valueAt(i, j, tab)) {
        is CellValue.Bytes -> v.data
        null -> tab.rows.getOrNull(i)?.getOrNull(j) as? ByteArray
        else -> null
    }
    fun loadBytes(i: Int, j: Int) {
        val path = chooseFile(false, tr("Cargar «%s» desde un archivo", cols[j].name)) ?: return
        val f = java.io.File(path)
        if (f.length() > MAX_LOAD_BYTES) { onNotice(tr("El archivo pesa más de %d MB: no se carga en una celda", MAX_LOAD_BYTES / 1_000_000)); return }
        runCatching { f.readBytes() }.onSuccess { editing?.setCell(i, j, CellValue.Bytes(f.name, it), tab) }.onFailure { onNotice(tr("No se pudo leer el archivo: %s", it.message)) }
    }
    fun saveBytes(i: Int, j: Int) {
        val data = bytesOf(i, j) ?: run { onNotice(tr("La celda no tiene contenido binario")); return }
        val path = chooseFile(true, tr("Guardar «%s» en un archivo", cols[j].name), cols[j].name + ".bin") ?: return
        runCatching { java.io.File(path).writeBytes(data) }.onSuccess { onNotice(tr("%d bytes guardados en %s", data.size, path)) }.onFailure { onNotice(tr("No se pudo guardar: %s", it.message)) }
    }
    // starts wide enough for the type and for the whole header name (icons, funnel and arrow included); dragging a header edge changes it
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(tab, cols) {
        cols.map { col ->
            val name = with(density) { measurer.measure(col.name, TextStyle(fontSize = 13.sp), maxLines = 1).size.width.toDp() }
            maxOf(columnWidth(col.typeName, editing?.engine), (name + HeaderChrome).coerceAtMost(MaxHeaderWidth))
        }.toMutableStateList()
    }
    /** Double click on a header edge: wide enough for the header and the longest loaded value (monospace, so the longest text), up to MaxFitWidth. */
    fun fitColumn(j: Int) {
        val head = with(density) { measurer.measure(cols[j].name, TextStyle(fontSize = 13.sp), maxLines = 1).size.width.toDp() } + HeaderChrome
        val longest = tab.rows.take(2000).maxOfOrNull { (it.getOrNull(j)?.let { v -> cellText(if (v is ByteArray) "<${v.size} bytes>" else v) } ?: "<null>").length } ?: 0
        val body = if (longest == 0) 0.dp else with(density) { measurer.measure("0".repeat(longest), MonoStyle, maxLines = 1).size.width.toDp() } + CellPadding
        widths[j] = maxOf(head, body).coerceIn(MinColumnWidth, MaxFitWidth)
    }
    fun isDefault(i: Int, j: Int) = editing?.valueAt(i, j, tab) is CellValue.Default
    fun raw(i: Int, j: Int): String = when (val v = editing?.valueAt(i, j, tab)) {
        is CellValue.Text -> v.text
        is CellValue.Null, is CellValue.Default, is CellValue.Bytes -> ""
        null -> tab.rows.getOrNull(i)?.getOrNull(j)?.toString().orEmpty()
    }

    fun startEdit(i: Int, j: Int, seed: String? = null) {
        val ed = editing ?: return
        val insertRow = ed.isInsertRow(i, tab)
        when {
            !insertRow && !ed.canEditRows -> { onNotice(ed.readOnlyReason() ?: tr("No se pueden editar las filas de esta tabla")); return }
            !insertRow && i in ed.deleted -> { onNotice(tr("La fila está marcada para borrar: revierte los cambios para editarla")); return }
            !ed.isEditable(j, tab) -> { onNotice(tr("«%s» es una columna binaria (%s): no se puede editar como texto", cols[j].name, cols[j].typeName)); return }
        }
        selected = i to j; editCell = i to j
        (seed ?: raw(i, j)).let { draft = TextFieldValue(it, TextRange(it.length)) }
    }
    fun finishEdit(save: Boolean) {
        val cell = editCell ?: return
        editCell = null
        if (save && editing != null && draft.text != raw(cell.first, cell.second)) editing.setCell(cell.first, cell.second, CellValue.Text(draft.text), tab)
        focus.requestFocus()
    }
    LaunchedEffect(newRows) {
        if (newRows > 0) { list.animateScrollToItem(visible.lastIndex.coerceAtLeast(0)); selected = (total - 1) to 0; editing?.cursorRow = total - 1 }
    }

    Box(
        modifier.background(c.editorBg).focusRequester(focus).focusTarget().testTag("grid").onPreviewKeyEvent { e ->
            val sel = selected
            if (editCell != null) return@onPreviewKeyEvent false
            val plain = !e.isMetaPressed && !e.isCtrlPressed && !e.isAltPressed
            when {
                // typing on a selected cell starts editing it with that character (the typed-character event follows the key press)
                e.type == KeyEventType.Unknown && plain && sel != null && editing != null && e.utf16CodePoint >= 32 && e.utf16CodePoint != 127 -> {
                    startEdit(sel.first, sel.second, String(Character.toChars(e.utf16CodePoint)))
                    editCell != null
                }
                e.type != KeyEventType.KeyDown -> false
                (e.isMetaPressed || e.isCtrlPressed) && e.key == Key.C && sel != null -> {
                    textOf(sel.first, sel.second)?.let { clipboard.setText(AnnotatedString(it)) }
                    true
                }
                (e.key == Key.Enter || e.key == Key.F2) && sel != null && editing != null -> { startEdit(sel.first, sel.second); true }
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().horizontalScroll(hscroll)) {
            Row(Modifier.height(HeaderHeight).background(c.headerBg)) {
                Box(Modifier.width(RowNumberWidth).fillMaxHeight().drawBehind { drawLine(c.windowBg, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) })
                cols.forEachIndexed { j, col ->
                    Box(Modifier.width(widths[j]).fillMaxHeight()) {
                    Tip(col.name, col.typeName.lowercase(), Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxSize().drawBehind { drawLine(c.windowBg, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        KIcon(IconKind.Column, tint = c.accentIcon, size = 14.dp)
                        // name and funnel share what the sort arrow leaves; a long name is cut, never the icons
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(col.name, Modifier.weight(1f, fill = false), fontSize = 13.sp, color = c.gridText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val active = j in tab.filters
                            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).clickable { filterOpen = if (filterOpen == j) null else j }.testTag("filter-btn:$j"), contentAlignment = Alignment.Center) {
                                KIcon(IconKind.Filter, tint = if (active) c.accent else c.textMuted, size = 12.dp)
                                if (filterOpen == j) ColumnFilterPopup(tab, j) { filterOpen = null }
                            }
                        }
                        // none -> ascending -> descending -> none
                        val dir = tab.sort?.takeIf { it.first == j }?.second
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).testTag("sort-btn:$j").clickable {
                                val next = when (dir) { null -> true; true -> false; false -> null }
                                if (onSort != null) onSort(j, next) else tab.sort = next?.let { j to it }
                            },
                            contentAlignment = Alignment.Center,
                        ) { KIcon(when (dir) { null -> IconKind.Updown; true -> IconKind.ArrowUp; false -> IconKind.ArrowDown }, tint = if (dir == null) c.textMuted else c.accent, size = 12.dp) }
                    }
                    }
                    Splitter(true, { widths[j] = (widths[j] + it).coerceAtLeast(MinColumnWidth) }, Modifier.align(Alignment.CenterEnd).testTag("col-resize:$j").pointerInput(j) { detectTapGestures(onDoubleTap = { fitColumn(j) }) })
                    }
                }
            }
            Box(Modifier.weight(1f).width(RowNumberWidth + widths.fold(0.dp) { a, w -> a + w })) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 44.dp)) {   // room for the floating rows pill
                    items(visible.size) { pos ->
                        val i = visible[pos]
                        val isNew = i >= tab.rows.size
                        val isDeleted = editing != null && i in editing.deleted
                        val rowSelected = selected?.first == i || (editing != null && i in editing.selectedRows)
                        Row(Modifier.height(RowHeight).testTag("row:$i")) {
                            Box(
                                Modifier.width(RowNumberWidth).fillMaxHeight().background(if (rowSelected) c.currentLine else Color.Transparent)
                                    .drawBehind { lines(c.gridLine) }.padding(end = 6.dp)
                                    .then(if (editing == null) Modifier else Modifier.testTag("rownum:$i").onPointerEvent(PointerEventType.Press) { e ->
                                        val m = e.keyboardModifiers
                                        when {
                                            m.isShiftPressed && anchorRow >= 0 -> { editing.selectedRows.clear(); (minOf(anchorRow, i)..maxOf(anchorRow, i)).forEach { editing.selectedRows.add(it) } }
                                            m.isMetaPressed || m.isCtrlPressed -> { if (i in editing.selectedRows) editing.selectedRows.remove(i) else editing.selectedRows.add(i); anchorRow = i }
                                            else -> { editing.selectedRows.clear(); editing.selectedRows.add(i); anchorRow = i }
                                        }
                                        editing.cursorRow = i
                                        focus.requestFocus()
                                    }),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Text(if (isNew) "+" else "${i + 1}", fontSize = 12.sp, style = MonoStyle.copy(fontSize = 12.sp), color = if (rowSelected) c.gutterTextCurrent else c.rowNumber) }
                            for (j in cols.indices) {
                                val isSel = selected == i to j
                                val pending = editing?.valueAt(i, j, tab)
                                val shown = textOf(i, j)
                                val bg = when {
                                    isDeleted -> c.pendingDelete
                                    isSel -> c.selection
                                    isNew -> c.pendingInsert
                                    pending != null -> c.pendingEdit
                                    else -> Color.Transparent
                                }
                                val isNumber = numeric[j]
                                val cell: @Composable () -> Unit = {
                                    Box(
                                        Modifier.testTag("cell:$i:$j").width(widths[j]).fillMaxHeight().background(bg).drawBehind { lines(c.gridLine) }
                                            .combinedClickable(
                                                enabled = editCell != i to j,      // while editing, clicks belong to the text field
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                                                onClick = { selected = i to j; editing?.cursorRow = i; editing?.selectedRows?.clear(); focus.requestFocus() },
                                                onDoubleClick = { startEdit(i, j) },
                                            )
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = if (isNumber) Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        if (editCell == i to j) {
                                            val kind = temporalKind(cols[j].typeName, editing?.engine)
                                            var pickerOpen by remember { mutableStateOf(true) }
                                            val requester = remember { FocusRequester() }
                                            var focused by remember { mutableStateOf(false) }
                                            // ask for the keyboard until the field really has it (the request can arrive before the node is ready)
                                            LaunchedEffect(Unit) { repeat(6) { if (!focused) { runCatching { requester.requestFocus() }; kotlinx.coroutines.delay(40) } } }
                                            BasicTextField(
                                                draft, { draft = it }, Modifier.fillMaxWidth().padding(end = if (kind != null) 18.dp else 0.dp).focusRequester(requester).testTag("cell-editor")
                                                    .onFocusChanged { st -> if (st.isFocused) focused = true else if (focused && editCell == i to j) finishEdit(true) }
                                                    .onPreviewKeyEvent { e ->
                                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                        when (e.key) {
                                                            Key.Enter, Key.NumPadEnter -> { finishEdit(true); true }
                                                            Key.Escape -> { finishEdit(false); true }
                                                            Key.Tab -> {
                                                                finishEdit(true)
                                                                val next = if (e.isShiftPressed) j - 1 else j + 1
                                                                if (next in cols.indices) startEdit(i, next)
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    },
                                                singleLine = true, textStyle = MonoStyle.copy(color = c.gridText), cursorBrush = SolidColor(c.text),
                                            )
                                            if (kind != null) {
                                                // the calendar icon shows or hides the picker; picking rewrites the editor text in the column's own format
                                                Box(Modifier.align(Alignment.CenterEnd).size(18.dp).testTag("picker-toggle").pointerInput(Unit) { detectTapGestures { pickerOpen = !pickerOpen } }, contentAlignment = Alignment.Center) {
                                                    KIcon(IconKind.Calendar, tint = if (pickerOpen) c.accentIcon else c.textSecondary, size = 14.dp)
                                                }
                                                if (pickerOpen) DatePickerPopup(
                                                    kind, draft.text,
                                                    onText = { t, done -> draft = TextFieldValue(t, TextRange(t.length)); if (done) finishEdit(true) },
                                                    onNull = { editCell = null; editing?.setCell(i, j, CellValue.Null, tab); focus.requestFocus() },
                                                    onAccept = { finishEdit(true) },
                                                )
                                            }
                                        } else {
                                            val isDef = isDefault(i, j)
                                            Text(
                                                when { isDef -> "<default>"; shown == null -> "<null>"; else -> shown.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎') },
                                                maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                                                style = MonoStyle.copy(textDecoration = if (isDeleted) TextDecoration.LineThrough else TextDecoration.None),
                                                color = if (shown == null || isDef) c.nullText else c.gridText,
                                                textAlign = if (isNumber) TextAlign.End else TextAlign.Start,
                                            )
                                        }
                                    }
                                }
                                if (editing == null) cell()
                                else ContextMenuArea(items = {
                                    buildList {
                                        add(ContextMenuItem(tr("Editar")) { startEdit(i, j) })
                                        if (editing.isEditable(j, tab)) add(ContextMenuItem(tr("Editar en ventana…")) { bigCell = i to j })
                                        else {
                                            add(ContextMenuItem(tr("Cargar desde archivo…")) { loadBytes(i, j) })
                                            add(ContextMenuItem(tr("Guardar en archivo…")) { saveBytes(i, j) })
                                        }
                                        add(ContextMenuItem(tr("Poner NULL")) { editing.setCell(i, j, CellValue.Null, tab) })
                                        if (isNew) add(ContextMenuItem(tr("Valor por defecto")) { editing.setCell(i, j, CellValue.Default, tab) })
                                        if (editing.isEditable(j, tab)) generators(cols[j].typeName, editing.engine).forEach { (label, make) ->
                                            add(ContextMenuItem(label) { editing.setCell(i, j, CellValue.Text(make()), tab) })
                                        }
                                        add(ContextMenuItem(tr("Copiar")) { clipboard.setText(AnnotatedString(shown.orEmpty())) })
                                    }
                                }) { cell() }
                            }
                        }
                    }
                }
            }
        }
        ListScrollbar(list, Modifier.align(Alignment.CenterEnd).padding(top = HeaderHeight).fillMaxHeight())
        val wide = hscroll.maxValue > 0
        if (wide) HorizontalScrollbar(rememberScrollbarAdapter(hscroll), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp).testTag("grid-hscroll"), style = scrollbarStyle())
        if (showRowsPill) RowsPill(tab, if (filtersSnapshot.isEmpty()) null else visible.count { it < tab.rows.size }, onLoadMore, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (wide) 18.dp else 10.dp))
        bigCell?.let { (i, j) ->
            BigCellEditor(
                "${cols[j].name} (${cols[j].typeName.lowercase()})", raw(i, j), isJsonType(cols[j].typeName),
                onSave = { text -> bigCell = null; if (editing != null && text != raw(i, j)) editing.setCell(i, j, CellValue.Text(text), tab) },
                onCancel = { bigCell = null },
            )
        }
    }
}

/** Order of two cell values: NULL is the smallest, numbers compare by value, text ignoring case, same-typed comparables by themselves, anything else as text. */
internal fun compareCells(a: Any?, b: Any?): Int = when {
    a == null || b == null -> if (a == null && b == null) 0 else if (a == null) -1 else 1
    a is Number && b is Number -> runCatching { java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString())) }.getOrElse { a.toDouble().compareTo(b.toDouble()) }
    a is String && b is String -> a.compareTo(b, ignoreCase = true)
    a is Comparable<*> && a::class == b::class -> @Suppress("UNCHECKED_CAST") (a as Comparable<Any>).compareTo(b)
    else -> a.toString().compareTo(b.toString(), ignoreCase = true)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.lines(color: androidx.compose.ui.graphics.Color) {
    drawLine(color, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

/** Floating "N filas" pill with a load-more button while the cursor has rows left. */
@Composable
private fun RowsPill(tab: ResultTab, filtered: Int?, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(32.dp).clip(shape).background(c.windowBg, shape).border(1.dp, c.border, shape).padding(horizontal = 10.dp).testTag("rows-pill").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text((if (filtered != null) tr("%s de ", filtered) else "") + tr("%d filas%s", tab.rows.size, if (tab.exhausted) "" else "+"), fontSize = 12.sp, color = c.text)
        if (!tab.exhausted && !tab.result.truncated) {
            VSeparator()
            if (tab.loading) Text(tr("Cargando…"), fontSize = 12.sp, color = c.textSecondary)
            else ToolButton(IconKind.Plus, tr("Cargar más"), onLoadMore)
        }
        if (tab.result.truncated) { VSeparator(); Text(tr("recortado"), fontSize = 12.sp, color = c.textSecondary, fontStyle = FontStyle.Italic) }
    }
}
