package ksqlc.drivers.dialect

/** Plain `DatabaseMetaData` introspection with generated DDL; stands in for SQL Server and Oracle until they get their own. */
object GenericDialect : MetaDialect() {
    override val hiddenNamespaces = setOf("information_schema", "sys")
}
