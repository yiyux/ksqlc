package ksqlc.drivers.dialect

/** What the Modify dialog offers for an engine, and what applying its statements guarantees. */
data class ModifyCaps(
    /** All the statements run in one transaction: if one fails nothing is left applied. */
    val atomic: Boolean = true,
    /** Keys and foreign keys can be deferrable. */
    val deferrable: Boolean = true,
    /** Index columns take a collation and operator class, and the index takes `include` columns and a condition. */
    val indexExtras: Boolean = true,
    /** Identity has min, max, step, cache and cycle (else it is only on or off, like `auto_increment`). */
    val identityOptions: Boolean = true,
    /** Identity is `auto_increment`: one per table, and it has to lead a key or index. */
    val singleIdentity: Boolean = false,
    /** A generated column can be virtual as well as stored. */
    val virtualColumns: Boolean = false,
    /** A foreign key's target is a database (catalog), not a schema. */
    val refInCatalog: Boolean = false,
    val indexMethods: List<String> = listOf("btree", "hash", "gist", "spgist", "gin", "brin"),
)
