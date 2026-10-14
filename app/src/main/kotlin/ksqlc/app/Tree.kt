package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ksqlc.core.*
import ksqlc.drivers.SchemaSource

enum class NodeKind { NAMESPACE, GROUP, TABLE, VIEW, MATERIALIZED_VIEW, SEQUENCE, ROUTINE, COLUMN, INDEX, FOREIGN_KEY }

/**
 * One row of the schema tree. [children] is null until loaded; [loader] (run off the UI thread)
 * produces them on first expand. Group nodes are created with their children already set.
 */
class TreeNode(
    val id: String,
    val label: String,
    val kind: NodeKind,
    val obj: DbObject? = null,
    val ns: Namespace? = null,
    val detail: String? = null,
    internal val loader: (() -> List<TreeNode>)? = null,
    preset: List<TreeNode>? = null,
) {
    var children by mutableStateOf(preset)
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val expandable: Boolean get() = loader != null || !children.isNullOrEmpty()
}

data class TreeRow(val node: TreeNode, val depth: Int)

private val GROUPS = listOf(
    DbObjectKind.TABLE to "Tablas",
    DbObjectKind.VIEW to "Vistas",
    DbObjectKind.MATERIALIZED_VIEW to "Vistas materializadas",
    DbObjectKind.SEQUENCE to "Secuencias",
    DbObjectKind.ROUTINE to "Rutinas",
)

class TreeModel(val source: SchemaSource, private val io: CoroutineDispatcher = Dispatchers.IO) {
    var roots by mutableStateOf<List<TreeNode>>(emptyList())
        private set

    /** Replaces the roots. Throws if the namespaces cannot be read, leaving the old tree in place. */
    suspend fun load() {
        roots = withContext(io) { source.namespaces() }.map(::namespaceNode)
    }

    suspend fun expand(node: TreeNode) {
        node.expanded = true
        val loader = node.loader
        if (node.children != null || loader == null) return
        node.loading = true
        node.error = null
        try {
            node.children = withContext(io) { loader() }
        } catch (e: Exception) {
            node.error = e.message ?: e.toString()   // children stay null, so the next expand retries
        } finally {
            node.loading = false
        }
    }

    /** Reloads everything and re-opens the nodes that were open before. */
    suspend fun refresh() {
        val open = buildSet { collectExpanded(roots, this) }
        load()
        reopen(roots, open)
    }

    private fun collectExpanded(nodes: List<TreeNode>, out: MutableSet<String>) {
        for (n in nodes) {
            if (n.expanded) out += n.id
            n.children?.let { collectExpanded(it, out) }
        }
    }

    private suspend fun reopen(nodes: List<TreeNode>, open: Set<String>) {
        for (n in nodes) if (n.id in open) {
            expand(n)
            n.children?.let { reopen(it, open) }
        }
    }

    private fun namespaceNode(ns: Namespace): TreeNode {
        val id = "ns:${ns.catalog.orEmpty()}.${ns.schema.orEmpty()}"
        return TreeNode(id, ns.label, NodeKind.NAMESPACE, ns = ns, loader = { groupsOf(id, source.objects(ns)) })
    }

    private fun groupsOf(parentId: String, objects: List<DbObject>): List<TreeNode> =
        GROUPS.mapNotNull { (kind, title) ->
            val items = objects.filter { it.kind == kind }
            if (items.isEmpty()) return@mapNotNull null
            val gid = "$parentId/$title"
            TreeNode(gid, "$title (${items.size})", NodeKind.GROUP, preset = items.map { objectNode(gid, it) })
        }

    private fun objectNode(parentId: String, o: DbObject): TreeNode {
        val id = "$parentId/${o.name}"
        val kind = when (o.kind) {
            DbObjectKind.TABLE -> NodeKind.TABLE
            DbObjectKind.VIEW -> NodeKind.VIEW
            DbObjectKind.MATERIALIZED_VIEW -> NodeKind.MATERIALIZED_VIEW
            DbObjectKind.SEQUENCE -> NodeKind.SEQUENCE
            DbObjectKind.ROUTINE -> NodeKind.ROUTINE
        }
        val loader = if (o.isReadable) ({ detailGroups(id, o, source.details(o)) }) else null
        return TreeNode(id, o.name, kind, obj = o, detail = o.comment, loader = loader)
    }

    private fun detailGroups(parentId: String, o: DbObject, d: TableDetails): List<TreeNode> = buildList {
        if (d.columns.isNotEmpty()) {
            val gid = "$parentId/cols"
            add(TreeNode(gid, "Columnas (${d.columns.size})", NodeKind.GROUP, obj = o, preset = d.columns.map { c ->
                TreeNode("$gid/${c.name}", c.name, NodeKind.COLUMN, obj = o, detail = columnDetail(c, c.name in d.primaryKey))
            }))
        }
        if (d.indexes.isNotEmpty()) {
            val gid = "$parentId/idx"
            add(TreeNode(gid, "Índices (${d.indexes.size})", NodeKind.GROUP, obj = o, preset = d.indexes.map { ix ->
                TreeNode("$gid/${ix.name}", ix.name, NodeKind.INDEX, obj = o,
                    detail = "(${ix.columns.joinToString()})" + if (ix.unique) " unique" else "")
            }))
        }
        if (d.foreignKeys.isNotEmpty()) {
            val gid = "$parentId/fks"
            add(TreeNode(gid, "Claves foráneas (${d.foreignKeys.size})", NodeKind.GROUP, obj = o, preset = d.foreignKeys.mapIndexed { n, fk ->
                TreeNode("$gid/${fk.name ?: n}", fk.name ?: fk.columns.joinToString(), NodeKind.FOREIGN_KEY, obj = o,
                    detail = "(${fk.columns.joinToString()}) → ${fk.refTable}(${fk.refColumns.joinToString()})")
            }))
        }
    }

    private fun columnDetail(c: ColumnInfo, pk: Boolean) = buildString {
        append(c.typeName)
        if (pk) append("  PK")
        if (!c.nullable) append("  not null")
        if (c.autoIncrement) append("  auto")
    }
}

/**
 * Rows to draw. Without a filter: open nodes only. With one (case-insensitive substring of the
 * label, over what is already loaded): matching nodes and their ancestors, with the ancestors forced open.
 */
fun visibleRows(roots: List<TreeNode>, filter: String): List<TreeRow> {
    val q = filter.trim().lowercase()
    val out = mutableListOf<TreeRow>()

    fun matches(n: TreeNode): Boolean = n.label.lowercase().contains(q) || (n.children?.any(::matches) ?: false)

    fun walk(nodes: List<TreeNode>, depth: Int) {
        for (n in nodes) {
            if (q.isEmpty()) {
                out += TreeRow(n, depth)
                if (n.expanded) n.children?.let { walk(it, depth + 1) }
            } else if (matches(n)) {
                out += TreeRow(n, depth)
                val kids = n.children
                if (kids != null && (n.expanded || kids.any(::matches))) walk(kids, depth + 1)
            }
        }
    }
    walk(roots, 0)
    return out
}
