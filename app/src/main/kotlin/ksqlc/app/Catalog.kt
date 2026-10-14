package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.core.Namespace
import ksqlc.drivers.SchemaSource
import ksqlc.sql.ColumnRef
import ksqlc.sql.SchemaInfo
import ksqlc.sql.TableRef
import ksqlc.sql.identifierAt
import ksqlc.sql.statementAt
import ksqlc.sql.tableRefs
import ksqlc.sql.QueryTable
import ksqlc.sql.find
import java.util.concurrent.ConcurrentHashMap

private const val MAX_NAMESPACES = 100
private const val MAX_OBJECTS = 20_000

/**
 * What the editor knows about the database: every table and view name (read once in the background) and,
 * on demand, their columns. Column lookups return null until the answer arrives; [version] then changes,
 * so anything that read the catalog from composition recomposes.
 */
class SchemaCatalog(private val source: SchemaSource, private val scope: CoroutineScope) : SchemaInfo {
    /** Bumped whenever something new becomes known. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var objects: List<Pair<TableRef, DbObject>> = emptyList()
    @Volatile private var namespaces: List<Namespace> = emptyList()
    @Volatile override var complete = false
        private set
    private val columnCache = ConcurrentHashMap<TableRef, List<ColumnRef>>()
    private val requested = ConcurrentHashMap.newKeySet<TableRef>()

    /** Reads namespaces and object names; call off the UI thread. */
    fun load() {
        val spaces = source.namespaces()
        val found = ArrayList<Pair<TableRef, DbObject>>()
        var truncated = spaces.size > MAX_NAMESPACES
        for (ns in spaces.take(MAX_NAMESPACES)) {
            for (o in source.objects(ns)) {
                if (!o.isReadable) continue
                if (found.size >= MAX_OBJECTS) { truncated = true; break }
                found += TableRef(ns.schema ?: ns.catalog, o.name, o.kind != DbObjectKind.TABLE) to o
            }
        }
        namespaces = spaces; objects = found; complete = !truncated
        version++
    }

    override fun schemas() = namespaces.mapNotNull { it.schema ?: it.catalog }.distinct()
    override fun tables() = objects.map { it.first }

    override fun columns(table: TableRef): List<ColumnRef>? {
        columnCache[table]?.let { return it }
        val o = objectOf(table) ?: return null
        if (requested.add(table)) scope.launch(Dispatchers.IO) {
            runCatching { source.details(o) }
                .onSuccess { d -> columnCache[table] = d.columns.map { ColumnRef(it.name, it.typeName) }; version++ }
                .onFailure { columnCache[table] = emptyList(); version++ }
        }
        return null
    }

    fun objectOf(table: TableRef): DbObject? = objects.firstOrNull { it.first == table }?.second
}

/** A table, and optionally one of its columns, that a name in the SQL text refers to. */
data class NameTarget(val table: TableRef, val column: String?)

/**
 * What the identifier at [offset] of [sql] points to, using [schema] and the tables the statement reads:
 * `alias.col` → that column of the aliased table, `schema.table`/`table` → the table, and a bare column name
 * → the column of whichever of the statement's tables has it. Null when nothing matches.
 */
fun resolveTarget(sql: String, offset: Int, schema: SchemaInfo): NameTarget? {
    val chain = identifierAt(sql, offset) ?: return null
    val p = chain.parts
    val range = statementAt(sql, offset) ?: return null
    val refs = tableRefs(sql, range).filter { !it.isCte }
    fun tableOf(q: QueryTable) = schema.find(q.schema, q.name)
    fun hasColumn(t: TableRef, name: String) = schema.columns(t)?.any { it.name.equals(name, true) } == true
    return when (p.size) {
        1 -> {
            val name = p[0]
            refs.firstOrNull { it.answersTo(name) }?.let { r -> tableOf(r)?.let { return NameTarget(it, null) } }
            schema.find(null, name)?.let { return NameTarget(it, null) }
            refs.mapNotNull { tableOf(it) }.firstOrNull { hasColumn(it, name) }?.let { NameTarget(it, p[0]) }
        }
        2 -> {
            val (q, x) = p
            val viaRef = refs.firstOrNull { it.answersTo(q) }?.let { tableOf(it) } ?: schema.find(null, q)
            when {
                chain.index == 0 -> viaRef?.let { NameTarget(it, null) }
                viaRef != null && hasColumn(viaRef, x) -> NameTarget(viaRef, x)
                else -> schema.find(q, x)?.let { NameTarget(it, null) }
            }
        }
        else -> {
            val t = schema.find(p[p.size - 3], p[p.size - 2]) ?: return null
            if (chain.index == p.size - 1) NameTarget(t, p.last()) else if (chain.index == p.size - 2) NameTarget(t, null) else null
        }
    }
}
