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
        conn?.takeIf { !it.isClosed } ?: DriverManager.getConnection(ds.url, ds.user, password).also { conn = it }

    @Synchronized override fun namespaces(): List<Namespace> = dialect.namespaces(connection())
    @Synchronized override fun objects(ns: Namespace): List<DbObject> = dialect.objects(connection(), ns)
    @Synchronized override fun details(o: DbObject): TableDetails = dialect.details(connection(), o)
    @Synchronized override fun ddl(o: DbObject): String = dialect.ddl(connection(), o)

    @Synchronized override fun close() {
        runCatching { conn?.close() }
        conn = null
    }
}
