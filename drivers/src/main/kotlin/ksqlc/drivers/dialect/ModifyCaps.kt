package ksqlc.drivers.dialect

/** What the Modify dialog offers for an engine, and what applying its statements guarantees. */
data class ModifyCaps(
    /** All the statements run in one transaction: if one fails nothing is left applied. */
    val atomic: Boolean = true,
    /** Statements that run first on the session that applies the change and are not part of it: a lock timeout, so a busy table fails instead of hanging. */
    val prelude: List<String> = emptyList(),
    /** Keys and foreign keys can be deferrable. */
    val deferrable: Boolean = true,
    /** Index columns take a collation and an operator class. */
    val indexColumnOptions: Boolean = true,
    /** An index takes `include` columns and a condition. */
    val indexExtras: Boolean = true,
    /** Identity has min, max, step, cache and cycle (else it is only on or off, like `auto_increment`). */
    val identityOptions: Boolean = true,
    /** There can be only one identity column in a table. */
    val singleIdentity: Boolean = false,
    /** The identity column has to lead a key or index (`auto_increment`). */
    val identityLeadsKey: Boolean = false,
    /** What the engine calls identity, for the checkbox and the messages. */
    val identityName: String = "Identity",
    /** Identity can only be set when the column is created: the engine cannot add it to a column or take it off. */
    val identityOnCreateOnly: Boolean = false,
    /** A generated column can be virtual as well as stored. */
    val virtualColumns: Boolean = false,
    /** A foreign key's target is a database (catalog), not a schema. */
    val refInCatalog: Boolean = false,
    /** A foreign key that stays needs a key or index starting with its columns: the engine refuses to drop the one it rests on. */
    val fkNeedsIndex: Boolean = false,
    val indexMethods: List<String> = listOf("btree", "hash", "gist", "spgist", "gin", "brin"),
    /** What a foreign key can do on delete and on update. */
    val fkActions: List<String> = listOf("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT"),
)
