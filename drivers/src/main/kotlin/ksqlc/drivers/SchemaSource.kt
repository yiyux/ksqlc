package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect

/** What the schema tree needs from a database; [Introspector] is the real implementation. */
interface SchemaSource {
    val dialect: Dialect
    fun namespaces(): List<Namespace>
    fun objects(ns: Namespace): List<DbObject>
    fun details(o: DbObject): TableDetails
    fun ddl(o: DbObject): String
    fun call(o: DbObject): List<String> = error("Este motor no tiene rutinas que ejecutar")
    fun tableModel(o: DbObject): TableModel? = null
}
