package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.sql.Connection
import java.sql.DriverManager

/**
 * Reads schema metadata over its own connection, so a long query or open transaction in the
 * console never blocks the tree. Calls are serialized because they share that one connection.
 */
class Introspector(private val ds: DataSource, private val password: String?) : SchemaSource, AutoCloseable {
    override val dialect: Dialect = Dialect.of(ds.kind)
    private var conn: Connection? = null

    @Synchronized private fun connection(): Connection =
        conn?.takeIf { !it.isClosed } ?: openConnection(ds, password).also { conn = it }

    /** Also list the engine's own schemas and objects; takes effect on the next read. */
    @Volatile var includeSystem = false

    /** When set, only the namespace with this name (any case) is listed: "single database" mode. */
    @Volatile var onlyNamespace: String? = null

    @Synchronized override fun namespaces(): List<Namespace> =
        dialect.namespaces(connection(), includeSystem).let { all -> onlyNamespace?.takeIf { it.isNotBlank() }?.let { only -> all.filter { it.label.equals(only, true) } } ?: all }
    @Synchronized override fun objects(ns: Namespace): List<DbObject> = dialect.objects(connection(), ns, includeSystem)
    @Synchronized override fun details(o: DbObject): TableDetails = dialect.details(connection(), o)
    @Synchronized override fun ddl(o: DbObject): String = dialect.ddl(connection(), o)

    @Synchronized override fun close() {
        runCatching { conn?.close() }
        conn = null
    }
}
