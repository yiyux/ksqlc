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

/** "Modify" for a table: the tree of its parts on the left, the form of the selected one on the right, the SQL it amounts to below. */
@Composable
fun ModifyDialog(state: AppState) {
    val draft = state.modifyDraft ?: return
    val c = Ksqlc.colors
    val sql = draft.statements
    val body = draft.problem ?: if (sql.isEmpty()) tr("-- sin cambios") else sql.joinToString("\n") { "$it;" }
    val preview = if (draft.problem == null && sql.isNotEmpty()) draft.notice?.let { "-- $it\n$body" } ?: body else body
    ModalOverlay({ if (!state.modifyBusy) state.closeModify() }, Modifier.testTag("modify-dialog"), width = 920.dp, height = 720.dp) {
        Column(Modifier.fillMaxSize().background(c.dialogBg)) {
            Box(Modifier.fillMaxWidth().height(29.dp), contentAlignment = Alignment.Center) {
                Text(tr("Modificar %s", draft.obj.name), fontSize = 13.sp, color = c.textSecondary)
            }
            Separator()
            Row(Modifier.weight(1f)) {
                ModifyTree(draft, Modifier.width(290.dp).fillMaxHeight())
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { ModifyForm(draft) }
            }
            Separator()
            Text(tr("Vista previa"), Modifier.padding(start = 12.dp, top = 6.dp), fontSize = 12.sp, color = c.textSecondary)
            Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
                Text(preview, Modifier.testTag("modify-preview"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (draft.problem != null) c.error else c.text)
            }
            state.modifyError?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("modify-error"), fontSize = 12.sp, color = c.error) }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction(tr("Cancelar"), state::closeModify, Modifier.testTag("modify-cancel"), enabled = !state.modifyBusy)
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
            ToolButton(IconKind.Plus, tr("Añadir"), { draft.add(sel.group) }, Modifier.testTag("modify-add"), enabled = sel.group != "table")
            ToolButton(IconKind.Minus, tr("Quitar"), { draft.remove(sel) }, Modifier.testTag("modify-remove"), enabled = sel.index >= 0)
            ToolButton(IconKind.Copy, tr("Duplicar"), { draft.duplicate(sel) }, Modifier.testTag("modify-duplicate"), enabled = sel.index >= 0)
        }
        Separator()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            node(Sel("table"), 0, IconKind.Table, m.name.ifEmpty { tr("(sin nombre)") }, null)
            group("columns", tr("columnas"), m.columns.size, IconKind.Column, m.columns.map { it.name to it.type })
            group("keys", tr("claves"), m.keys.size, IconKind.Key, m.keys.map { it.name to (if (it.primary) tr("primaria ") else "") + "(${it.columns.joinToString()})" })
            group("fks", tr("claves foráneas"), m.foreignKeys.size, IconKind.ForeignKey, m.foreignKeys.map { it.name to "(${it.columns.joinToString()}) → ${it.refTable}" })
            group("indexes", tr("índices"), m.indexes.size, IconKind.Index, m.indexes.map { it.name to (if (it.unique) tr("único ") else "") + "(${it.columns.joinToString { c -> c.name }})" })
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
                ToolButton(IconKind.ArrowUp, tr("Subir"), { onChange(items.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, tr("Bajar"), { onChange(items.moved(i, i + 1)) }, enabled = i < items.lastIndex)
                ToolButton(IconKind.Minus, tr("Quitar"), { onChange(items.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, tr("Añadir"), { onChange(items + (choices?.firstOrNull { it !in items } ?: "")) }, Modifier.testTag("$tag:add"))
    }
}

@Composable
private fun IndexColumnsEditor(cols: List<IndexColumn>, columnNames: List<String>, extras: Boolean, onChange: (List<IndexColumn>) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cols.forEachIndexed { i, ic ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FText(ic.name, { v -> onChange(cols.replaced(i, ic.copy(name = v, expression = v.trim() !in columnNames))) }, "modify-ix-col:$i", tr("columna o expresión"), modifier = Modifier.weight(1f))
                ChoiceField(ic.order.ifEmpty { "—" }, listOf("—", "ASC", "DESC"), { v -> onChange(cols.replaced(i, ic.copy(order = if (v == "—") "" else v))) }, "modify-ix-order:$i", Modifier.width(84.dp))
                if (extras) {
                    FText(ic.collation.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(collation = v.orNull()))) }, "modify-ix-coll:$i", tr("colación"), modifier = Modifier.width(90.dp))
                    FText(ic.opClass.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(opClass = v.orNull()))) }, "modify-ix-opc:$i", tr("clase op."), modifier = Modifier.width(90.dp))
                }
                ToolButton(IconKind.ArrowUp, tr("Subir"), { onChange(cols.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, tr("Bajar"), { onChange(cols.moved(i, i + 1)) }, enabled = i < cols.lastIndex)
                ToolButton(IconKind.Minus, tr("Quitar"), { onChange(cols.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, tr("Añadir columna"), { onChange(cols + IndexColumn(columnNames.firstOrNull { n -> cols.none { it.name == n } } ?: "")) }, Modifier.testTag("modify-ix-col:add"))
    }
}

@Composable
private fun ModifyForm(draft: ModifyDraft) {
    val m = draft.model
    val caps = draft.caps
    val sel = draft.selected
    val names = m.columns.map { it.name }
    val i = sel.index
    when {
        sel.group == "table" -> {
            MField(tr("Nombre:")) { FText(m.name, draft::rename, "modify-name") }
            MField(tr("Comentario:")) { FText(m.comment.orEmpty(), draft::comment, "modify-comment") }
        }
        i < 0 -> Text(tr("Elige un elemento de la lista o pulsa + para añadir uno."), fontSize = 13.sp, color = Ksqlc.colors.textSecondary)
        sel.group == "columns" && i < m.columns.size -> ColumnForm(draft, i)
        sel.group == "keys" && i < m.keys.size -> {
            val k = m.keys[i]
            MField(tr("Nombre:")) { FText(k.name, { v -> draft.updateKey(i) { it.copy(name = v) } }, "modify-key-name") }
            MField("") { KCheckbox(k.primary, { v -> draft.updateKey(i) { it.copy(primary = v) } }, label = tr("Primaria"), modifier = Modifier.testTag("modify-key-primary")) }
            if (caps.deferrable) {
                MField("") { KCheckbox(k.deferrable, { v -> draft.updateKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = tr("Diferible"), modifier = Modifier.testTag("modify-key-deferrable")) }
                MField("") { KCheckbox(k.initiallyDeferred, { v -> draft.updateKey(i) { it.copy(initiallyDeferred = v) } }, label = tr("Inicialmente diferida"), enabled = k.deferrable, modifier = Modifier.testTag("modify-key-initially")) }
            }
            MField(tr("Columnas:")) { StringListEditor(k.columns, { v -> draft.updateKey(i) { it.copy(columns = v) } }, names, "modify-key-col") }
        }
        sel.group == "fks" && i < m.foreignKeys.size -> {
            val f = m.foreignKeys[i]
            MField(tr("Nombre:")) { FText(f.name, { v -> draft.updateForeignKey(i) { it.copy(name = v) } }, "modify-fk-name") }
            MField(tr("Columnas:")) { StringListEditor(f.columns, { v -> draft.updateForeignKey(i) { it.copy(columns = v) } }, names, "modify-fk-col") }
            if (caps.refInCatalog) MField(tr("Base de datos referida:")) { FText(f.refNs.catalog.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(v.orNull(), null)) } }, "modify-fk-schema") }
            else MField(tr("Esquema referido:")) { FText(f.refNs.schema.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(null, v.orNull())) } }, "modify-fk-schema") }
            MField(tr("Tabla referida:")) { FText(f.refTable, { v -> draft.updateForeignKey(i) { it.copy(refTable = v) } }, "modify-fk-table") }
            MField(tr("Columnas referidas:")) { StringListEditor(f.refColumns, { v -> draft.updateForeignKey(i) { it.copy(refColumns = v) } }, null, "modify-fk-refcol") }
            MField("On delete:") { ChoiceField(f.onDelete, caps.fkActions, { v -> draft.updateForeignKey(i) { it.copy(onDelete = v) } }, "modify-fk-delete") }
            MField("On update:") { ChoiceField(f.onUpdate, caps.fkActions, { v -> draft.updateForeignKey(i) { it.copy(onUpdate = v) } }, "modify-fk-update") }
            if (caps.deferrable) MField("") { KCheckbox(f.deferrable, { v -> draft.updateForeignKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = tr("Diferible"), modifier = Modifier.testTag("modify-fk-deferrable")) }
        }
        sel.group == "indexes" && i < m.indexes.size -> {
            val x = m.indexes[i]
            MField(tr("Nombre:")) { FText(x.name, { v -> draft.updateIndex(i) { it.copy(name = v) } }, "modify-ix-name") }
            MField("") { KCheckbox(x.unique, { v -> draft.updateIndex(i) { it.copy(unique = v) } }, label = tr("Único"), modifier = Modifier.testTag("modify-ix-unique")) }
            MField(tr("Método de acceso:")) { ChoiceField(x.method, caps.indexMethods, { v -> draft.updateIndex(i) { it.copy(method = v) } }, "modify-ix-method") }
            MField(tr("Columnas:")) { IndexColumnsEditor(x.columns, names, caps.indexColumnOptions) { v -> draft.updateIndex(i) { it.copy(columns = v) } } }
            if (caps.indexExtras) {
                MField(tr("Incluir columnas:")) { StringListEditor(x.include, { v -> draft.updateIndex(i) { it.copy(include = v) } }, names, "modify-ix-inc") }
                MField(tr("Condición:")) { FText(x.condition.orEmpty(), { v -> draft.updateIndex(i) { it.copy(condition = v.orNull()) } }, "modify-ix-cond", tr("p. ej. (activo)")) }
            }
        }
        sel.group == "checks" && i < m.checks.size -> {
            val ck = m.checks[i]
            MField(tr("Nombre:")) { FText(ck.name, { v -> draft.updateCheck(i) { it.copy(name = v) } }, "modify-ck-name") }
            MField(tr("Expresión:")) { FText(ck.expression, { v -> draft.updateCheck(i) { it.copy(expression = v) } }, "modify-ck-expr", tr("p. ej. (precio > 0)")) }
        }
    }
}

@Composable
private fun ColumnForm(draft: ModifyDraft, i: Int) {
    val col = draft.model.columns[i]
    val caps = draft.caps
    val ident = col.identity
    fun edit(f: (ColumnModel) -> ColumnModel) = draft.updateColumn(i, f)
    fun editIdentity(f: (Identity) -> Identity) = edit { it.copy(identity = it.identity?.let(f)) }
    MField(tr("Nombre:")) { FText(col.name, { v -> edit { it.copy(name = v) } }, "modify-col-name") }
    MField(tr("Comentario:")) { FText(col.comment.orEmpty(), { v -> edit { it.copy(comment = v.orNull()) } }, "modify-col-comment") }
    MField(tr("Tipo de dato:")) { FText(col.type, { v -> edit { it.copy(type = v) } }, "modify-col-type") }
    MField("") { KCheckbox(col.notNull, { v -> edit { it.copy(notNull = v) } }, label = "Not null", enabled = ident == null, modifier = Modifier.testTag("modify-col-notnull")) }
    MField(tr("Valor por defecto:")) { FText(col.default.orEmpty(), { v -> edit { it.copy(default = v.orNull()) } }, "modify-col-default", enabled = ident == null && col.generated == null) }
    MField(tr("Generada como:")) { FText(col.generated.orEmpty(), { v -> edit { it.copy(generated = v.orNull()) } }, "modify-col-generated", tr("solo al crear la columna"), enabled = col.origin == null && ident == null) }
    if (caps.virtualColumns) MField("") { KCheckbox(col.generatedStored, { v -> edit { it.copy(generatedStored = v) } }, label = tr("Almacenada"), enabled = col.origin == null && col.generated != null, modifier = Modifier.testTag("modify-col-stored")) }
    MField("") {
        KCheckbox(ident != null, { on -> edit { it.copy(identity = if (on) Identity() else null, notNull = it.notNull || on, default = if (on) null else it.default) } }, label = caps.identityName, enabled = !(caps.identityOnCreateOnly && col.origin != null), modifier = Modifier.testTag("modify-col-identity"))
    }
    if (ident != null && caps.identityOptions) {
        MField("") { KCheckbox(ident.always, { v -> editIdentity { it.copy(always = v) } }, label = tr("Siempre (always)"), modifier = Modifier.testTag("modify-col-always")) }
        MField(tr("Mínimo:")) { FText(ident.min.orEmpty(), { v -> editIdentity { it.copy(min = v.orNull()) } }, "modify-col-min") }
        MField(tr("Máximo:")) { FText(ident.max.orEmpty(), { v -> editIdentity { it.copy(max = v.orNull()) } }, "modify-col-max") }
        MField(tr("Paso:")) { FText(ident.step.orEmpty(), { v -> editIdentity { it.copy(step = v.orNull()) } }, "modify-col-step") }
        MField(tr("Caché:")) { FText(ident.cache.orEmpty(), { v -> editIdentity { it.copy(cache = v.orNull()) } }, "modify-col-cache") }
        MField("") { KCheckbox(ident.cycle, { v -> editIdentity { it.copy(cycle = v) } }, label = tr("Cíclica"), modifier = Modifier.testTag("modify-col-cycle")) }
    }
}
