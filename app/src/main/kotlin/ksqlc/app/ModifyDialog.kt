package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.*

// Not trimmed: the field is controlled, so trimming would eat the space as it is typed.
private fun String.orNull() = ifBlank { null }
private val ACTIONS = listOf("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT")
private val METHODS = listOf("btree", "hash", "gist", "spgist", "gin", "brin")

/** "Modify" for a table: the tree of its parts on the left, the form of the selected one on the right, the SQL it amounts to below. */
@Composable
fun ModifyDialog(state: AppState) {
    val draft = state.modifyDraft ?: return
    val c = Ksqlc.colors
    val sql = draft.statements
    val preview = draft.problem ?: if (sql.isEmpty()) "-- sin cambios" else sql.joinToString("\n") { "$it;" }
    ModalOverlay({ if (!state.modifyBusy) state.closeModify() }, Modifier.testTag("modify-dialog"), width = 920.dp, height = 720.dp) {
        Column(Modifier.fillMaxSize().background(c.dialogBg)) {
            Box(Modifier.fillMaxWidth().height(29.dp), contentAlignment = Alignment.Center) {
                Text("Modificar ${draft.obj.name}", fontSize = 13.sp, color = c.textSecondary)
            }
            Separator()
            Row(Modifier.weight(1f)) {
                ModifyTree(draft, Modifier.width(290.dp).fillMaxHeight())
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { ModifyForm(draft) }
            }
            Separator()
            Text("Vista previa", Modifier.padding(start = 12.dp, top = 6.dp), fontSize = 12.sp, color = c.textSecondary)
            Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
                Text(preview, Modifier.testTag("modify-preview"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (draft.problem != null) c.error else c.text)
            }
            state.modifyError?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("modify-error"), fontSize = 12.sp, color = c.error) }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", state::closeModify, Modifier.testTag("modify-cancel"), enabled = !state.modifyBusy)
                TextAction("OK", state::applyModify, Modifier.testTag("modify-ok"), enabled = sql.isNotEmpty() && !state.modifyBusy, primary = true)
            }
        }
    }
}

@Composable
private fun ModifyTree(draft: ModifyDraft, modifier: Modifier) {
    val m = draft.model
    val sel = draft.selected
    @Composable fun node(s: Sel, depth: Int, icon: IconKind, label: String, detail: String?) {
        Box(Modifier.clickable { draft.selected = s }.testTag("modify:${s.group}:${s.index}")) {
            TreeLine(depth, expandable = false, expanded = true, icon = icon, label = label, detail = detail, selected = sel == s)
        }
    }
    @Composable fun group(g: String, title: String, count: Int, icon: IconKind, items: List<Pair<String, String?>>) {
        node(Sel(g), 1, IconKind.Folder, "$title $count", null)
        items.forEachIndexed { i, (label, detail) -> node(Sel(g, i), 2, icon, label, detail) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Plus, "Añadir", { draft.add(sel.group) }, Modifier.testTag("modify-add"), enabled = sel.group != "table")
            ToolButton(IconKind.Minus, "Quitar", { draft.remove(sel) }, Modifier.testTag("modify-remove"), enabled = sel.index >= 0)
            ToolButton(IconKind.Copy, "Duplicar", { draft.duplicate(sel) }, Modifier.testTag("modify-duplicate"), enabled = sel.index >= 0)
        }
        Separator()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            node(Sel("table"), 0, IconKind.Table, m.name.ifEmpty { "(sin nombre)" }, null)
            group("columns", "columnas", m.columns.size, IconKind.Column, m.columns.map { it.name to it.type })
            group("keys", "claves", m.keys.size, IconKind.Key, m.keys.map { it.name to (if (it.primary) "primaria " else "") + "(${it.columns.joinToString()})" })
            group("fks", "claves foráneas", m.foreignKeys.size, IconKind.ForeignKey, m.foreignKeys.map { it.name to "(${it.columns.joinToString()}) → ${it.refTable}" })
            group("indexes", "índices", m.indexes.size, IconKind.Index, m.indexes.map { it.name to (if (it.unique) "único " else "") + "(${it.columns.joinToString { c -> c.name }})" })
            group("checks", "checks", m.checks.size, IconKind.Check, m.checks.map { it.name to it.expression })
        }
    }
}

@Composable
private fun MField(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(140.dp).padding(top = 6.dp), fontSize = 13.sp, color = Ksqlc.colors.text)
        content()
    }
}

@Composable
private fun FText(value: String, onChange: (String) -> Unit, tag: String, placeholder: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) =
    CompactTextField(value, onChange, modifier, placeholder = placeholder, enabled = enabled, testTag = tag, height = 28.dp)

@Composable
private fun ChoiceField(value: String, options: List<String>, onChange: (String) -> Unit, tag: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        DropdownField(value, { open = true }, Modifier.fillMaxWidth().testTag(tag))
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o, fontSize = 13.sp) }, modifier = Modifier.testTag("$tag:$o"), onClick = { open = false; onChange(o) }) }
        }
    }
}

/** Names one per row, with up, down and remove; [choices] (when given) are picked from a menu, else typed. */
@Composable
private fun StringListEditor(items: List<String>, onChange: (List<String>) -> Unit, choices: List<String>?, tag: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (choices != null) ChoiceField(item.ifEmpty { "—" }, choices, { onChange(items.replaced(i, it)) }, "$tag:$i", Modifier.weight(1f))
                else FText(item, { onChange(items.replaced(i, it)) }, "$tag:$i", modifier = Modifier.weight(1f))
                ToolButton(IconKind.ArrowUp, "Subir", { onChange(items.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, "Bajar", { onChange(items.moved(i, i + 1)) }, enabled = i < items.lastIndex)
                ToolButton(IconKind.Minus, "Quitar", { onChange(items.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, "Añadir", { onChange(items + (choices?.firstOrNull { it !in items } ?: "")) }, Modifier.testTag("$tag:add"))
    }
}

@Composable
private fun IndexColumnsEditor(cols: List<IndexColumn>, columnNames: List<String>, onChange: (List<IndexColumn>) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cols.forEachIndexed { i, ic ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FText(ic.name, { v -> onChange(cols.replaced(i, ic.copy(name = v, expression = v.trim() !in columnNames))) }, "modify-ix-col:$i", "columna o expresión", modifier = Modifier.weight(1f))
                ChoiceField(ic.order.ifEmpty { "—" }, listOf("—", "ASC", "DESC"), { v -> onChange(cols.replaced(i, ic.copy(order = if (v == "—") "" else v))) }, "modify-ix-order:$i", Modifier.width(84.dp))
                FText(ic.collation.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(collation = v.orNull()))) }, "modify-ix-coll:$i", "colación", modifier = Modifier.width(90.dp))
                FText(ic.opClass.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(opClass = v.orNull()))) }, "modify-ix-opc:$i", "clase op.", modifier = Modifier.width(90.dp))
                ToolButton(IconKind.ArrowUp, "Subir", { onChange(cols.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, "Bajar", { onChange(cols.moved(i, i + 1)) }, enabled = i < cols.lastIndex)
                ToolButton(IconKind.Minus, "Quitar", { onChange(cols.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, "Añadir columna", { onChange(cols + IndexColumn(columnNames.firstOrNull { n -> cols.none { it.name == n } } ?: "")) }, Modifier.testTag("modify-ix-col:add"))
    }
}

@Composable
private fun ModifyForm(draft: ModifyDraft) {
    val m = draft.model
    val sel = draft.selected
    val names = m.columns.map { it.name }
    val i = sel.index
    when {
        sel.group == "table" -> {
            MField("Nombre:") { FText(m.name, draft::rename, "modify-name") }
            MField("Comentario:") { FText(m.comment.orEmpty(), draft::comment, "modify-comment") }
        }
        i < 0 -> Text("Elige un elemento de la lista o pulsa + para añadir uno.", fontSize = 13.sp, color = Ksqlc.colors.textSecondary)
        sel.group == "columns" && i < m.columns.size -> ColumnForm(draft, i)
        sel.group == "keys" && i < m.keys.size -> {
            val k = m.keys[i]
            MField("Nombre:") { FText(k.name, { v -> draft.updateKey(i) { it.copy(name = v) } }, "modify-key-name") }
            MField("") { KCheckbox(k.primary, { v -> draft.updateKey(i) { it.copy(primary = v) } }, label = "Primaria", modifier = Modifier.testTag("modify-key-primary")) }
            MField("") { KCheckbox(k.deferrable, { v -> draft.updateKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = "Diferible", modifier = Modifier.testTag("modify-key-deferrable")) }
            MField("") { KCheckbox(k.initiallyDeferred, { v -> draft.updateKey(i) { it.copy(initiallyDeferred = v) } }, label = "Inicialmente diferida", enabled = k.deferrable, modifier = Modifier.testTag("modify-key-initially")) }
            MField("Columnas:") { StringListEditor(k.columns, { v -> draft.updateKey(i) { it.copy(columns = v) } }, names, "modify-key-col") }
        }
        sel.group == "fks" && i < m.foreignKeys.size -> {
            val f = m.foreignKeys[i]
            MField("Nombre:") { FText(f.name, { v -> draft.updateForeignKey(i) { it.copy(name = v) } }, "modify-fk-name") }
            MField("Columnas:") { StringListEditor(f.columns, { v -> draft.updateForeignKey(i) { it.copy(columns = v) } }, names, "modify-fk-col") }
            MField("Esquema referido:") { FText(f.refNs.schema.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(null, v.orNull())) } }, "modify-fk-schema") }
            MField("Tabla referida:") { FText(f.refTable, { v -> draft.updateForeignKey(i) { it.copy(refTable = v) } }, "modify-fk-table") }
            MField("Columnas referidas:") { StringListEditor(f.refColumns, { v -> draft.updateForeignKey(i) { it.copy(refColumns = v) } }, null, "modify-fk-refcol") }
            MField("On delete:") { ChoiceField(f.onDelete, ACTIONS, { v -> draft.updateForeignKey(i) { it.copy(onDelete = v) } }, "modify-fk-delete") }
            MField("On update:") { ChoiceField(f.onUpdate, ACTIONS, { v -> draft.updateForeignKey(i) { it.copy(onUpdate = v) } }, "modify-fk-update") }
            MField("") { KCheckbox(f.deferrable, { v -> draft.updateForeignKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = "Diferible", modifier = Modifier.testTag("modify-fk-deferrable")) }
        }
        sel.group == "indexes" && i < m.indexes.size -> {
            val x = m.indexes[i]
            MField("Nombre:") { FText(x.name, { v -> draft.updateIndex(i) { it.copy(name = v) } }, "modify-ix-name") }
            MField("") { KCheckbox(x.unique, { v -> draft.updateIndex(i) { it.copy(unique = v) } }, label = "Único", modifier = Modifier.testTag("modify-ix-unique")) }
            MField("Método de acceso:") { ChoiceField(x.method, METHODS, { v -> draft.updateIndex(i) { it.copy(method = v) } }, "modify-ix-method") }
            MField("Columnas:") { IndexColumnsEditor(x.columns, names) { v -> draft.updateIndex(i) { it.copy(columns = v) } } }
            MField("Incluir columnas:") { StringListEditor(x.include, { v -> draft.updateIndex(i) { it.copy(include = v) } }, names, "modify-ix-inc") }
            MField("Condición:") { FText(x.condition.orEmpty(), { v -> draft.updateIndex(i) { it.copy(condition = v.orNull()) } }, "modify-ix-cond", "p. ej. (activo)") }
        }
        sel.group == "checks" && i < m.checks.size -> {
            val ck = m.checks[i]
            MField("Nombre:") { FText(ck.name, { v -> draft.updateCheck(i) { it.copy(name = v) } }, "modify-ck-name") }
            MField("Expresión:") { FText(ck.expression, { v -> draft.updateCheck(i) { it.copy(expression = v) } }, "modify-ck-expr", "p. ej. (precio > 0)") }
        }
    }
}

@Composable
private fun ColumnForm(draft: ModifyDraft, i: Int) {
    val col = draft.model.columns[i]
    val ident = col.identity
    fun edit(f: (ColumnModel) -> ColumnModel) = draft.updateColumn(i, f)
    fun editIdentity(f: (Identity) -> Identity) = edit { it.copy(identity = it.identity?.let(f)) }
    MField("Nombre:") { FText(col.name, { v -> edit { it.copy(name = v) } }, "modify-col-name") }
    MField("Comentario:") { FText(col.comment.orEmpty(), { v -> edit { it.copy(comment = v.orNull()) } }, "modify-col-comment") }
    MField("Tipo de dato:") { FText(col.type, { v -> edit { it.copy(type = v) } }, "modify-col-type") }
    MField("") { KCheckbox(col.notNull, { v -> edit { it.copy(notNull = v) } }, label = "Not null", enabled = ident == null, modifier = Modifier.testTag("modify-col-notnull")) }
    MField("Valor por defecto:") { FText(col.default.orEmpty(), { v -> edit { it.copy(default = v.orNull()) } }, "modify-col-default", enabled = ident == null && col.generated == null) }
    MField("Generada como:") { FText(col.generated.orEmpty(), { v -> edit { it.copy(generated = v.orNull()) } }, "modify-col-generated", "solo al crear la columna", enabled = col.origin == null && ident == null) }
    MField("") {
        KCheckbox(ident != null, { on -> edit { it.copy(identity = if (on) Identity() else null, notNull = it.notNull || on, default = if (on) null else it.default) } }, label = "Identity", modifier = Modifier.testTag("modify-col-identity"))
    }
    if (ident != null) {
        MField("") { KCheckbox(ident.always, { v -> editIdentity { it.copy(always = v) } }, label = "Siempre (always)", modifier = Modifier.testTag("modify-col-always")) }
        MField("Mínimo:") { FText(ident.min.orEmpty(), { v -> editIdentity { it.copy(min = v.orNull()) } }, "modify-col-min") }
        MField("Máximo:") { FText(ident.max.orEmpty(), { v -> editIdentity { it.copy(max = v.orNull()) } }, "modify-col-max") }
        MField("Paso:") { FText(ident.step.orEmpty(), { v -> editIdentity { it.copy(step = v.orNull()) } }, "modify-col-step") }
        MField("Caché:") { FText(ident.cache.orEmpty(), { v -> editIdentity { it.copy(cache = v.orNull()) } }, "modify-col-cache") }
        MField("") { KCheckbox(ident.cycle, { v -> editIdentity { it.copy(cycle = v) } }, label = "Cíclica", modifier = Modifier.testTag("modify-col-cycle")) }
    }
}
