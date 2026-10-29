package ksqlc.drivers

import ksqlc.core.tr

import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.core.StatementResult
import ksqlc.core.TableDetails
import ksqlc.drivers.dialect.Dialect

/** The columns a copy carries: [from] in the source table and [to] in the target, in the same order. */
class CopyColumns(val from: List<String>, val to: List<String>)

/**
 * Why a table cannot be copied, or null when it can. [targetExists]: the target table is already there (rows are added);
 * otherwise it is created, which only works between tables of the same engine because column types are not translated.
 */
fun copyProblem(source: DbKind, target: DbKind, targetReadOnly: Boolean, targetExists: Boolean, columns: CopyColumns?): String? = when {
    setOf(DbKind.CASSANDRA, DbKind.TRINO).any { it in setOf(source, target) } -> tr("Cassandra y Trino no tienen transacciones: una copia que falle a medias dejaría filas")
    targetReadOnly -> tr("La conexión de destino es de solo lectura")
    !targetExists && !sameEngine(source, target) -> tr("La tabla de destino no existe y no se crea entre motores distintos: los tipos no se traducen")
    columns != null && columns.from.isEmpty() -> tr("Las tablas no tienen ninguna columna con el mismo nombre")
    else -> null
}

private fun sameEngine(a: DbKind, b: DbKind) = a == b || setOf(a, b) == setOf(DbKind.MYSQL, DbKind.MARIADB)

/**
 * The columns of [src] that [dst] also has (by name, ignoring case), without the ones in [skip] (generated, lower-case).
 * With no target table ([dst] null) it is every column of [src].
 */
fun copyColumns(src: TableDetails, dst: TableDetails?, skip: Set<String> = emptySet()): CopyColumns {
    val pairs = src.columns.mapNotNull { c ->
        if (dst == null) c.name to c.name
        else dst.columns.firstOrNull { it.name.equals(c.name, ignoreCase = true) }?.let { c.name to it.name }
    }.filter { it.second.lowercase() !in skip }
    return CopyColumns(pairs.map { it.first }, pairs.map { it.second })
}

/** The insert that fills [target]; on SQL Server a table with an identity column among [cols] carries its own `set identity_insert`. */
fun copyInsertSql(dialect: Dialect, kind: DbKind, target: DbObject, cols: List<String>, identity: Set<String>): String {
    val table = dialect.qualified(target)
    val insert = "insert into $table (${cols.joinToString(", ") { dialect.quote(it) }}) values (${cols.joinToString(", ") { "?" }})"
    // a prepared statement runs in a scope of its own on SQL Server, where an earlier `set` is gone: it goes in the same batch
    return if (kind == DbKind.MSSQL && cols.any { it.lowercase() in identity }) "set identity_insert $table on; $insert; set identity_insert $table off" else insert
}

/**
 * Reads [cols] of [source] through [from] and inserts them into [target] ([toCols]) through [to], streaming, in one transaction of
 * [to]: all or nothing. [cancelled] fails the copy and so leaves the target as it was. Returns the rows copied.
 * shortcut: a table created by [create] is dropped again when the copy fails, because MySQL and Oracle confirm DDL by themselves.
 */
fun copyRows(
    from: Session, fromDialect: Dialect, source: DbObject, cols: List<String>,
    to: Session, toDialect: Dialect, toKind: DbKind, target: DbObject, toCols: List<String>, identity: Set<String>,
    create: String? = null, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {},
): Result<Long> {
    if (create != null) to.execute(listOf(stmt(create, emptyList()))).onFailure { return Result.failure(it) }
    val sql = "select ${cols.joinToString(", ") { fromDialect.quote(it) }} from ${fromDialect.qualified(source)}"
    val result = runCatching {
        when (val r = from.runScript(listOf(sql), keepRows = 0).single()) {
            is StatementResult.Failed -> error("${source.name}: ${r.message}")
            is StatementResult.Updated -> error(tr("%s: la consulta no devolvió filas", source.name))
            is StatementResult.Rows -> r.cursor.use { c ->
                var buffer = emptyList<List<Any?>>().iterator()
                val rows = object : Iterator<List<Any?>> {
                    override fun hasNext(): Boolean {
                        if (cancelled()) throw CancellationException(tr("Copia cancelada"))
                        if (!buffer.hasNext() && !c.exhausted) buffer = c.fetch(1000).iterator()
                        return buffer.hasNext()
                    }
                    override fun next() = buffer.next().map { bindable(it, toKind) }
                }
                to.executeBatch(copyInsertSql(toDialect, toKind, target, toCols, identity), rows, onProgress = onProgress).getOrThrow()
            }
        }
    }
    if (result.isFailure && create != null) runCatching { to.execute(listOf(stmt("drop table ${toDialect.qualified(target)}", emptyList()))) }
    return result
}

private class CancellationException(message: String) : RuntimeException(message)
