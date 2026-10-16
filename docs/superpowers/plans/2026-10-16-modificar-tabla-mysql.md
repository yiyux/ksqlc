# Modificar tabla en MySQL y MariaDB — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Modificar…» sobre una tabla de MySQL 8.0.19+ o MariaDB 10.5.2+ abre el mismo diálogo que en PostgreSQL, con vista previa del SQL y aplicación sentencia a sentencia.

**Architecture:** `MySqlDialect` implementa `tableModel` (lee `information_schema`) y `alterTable` (función pura en `MySqlAlter.kt`). Lo que el emparejado por `origin` tiene en común con PostgreSQL se extrae a `TableChanges.kt`. Una `ModifyCaps` por dialecto dice qué campos muestra el diálogo y si el motor aplica todo o nada; sin DDL transaccional se aplica con `runStepwise`, que para en el primer error.

**Tech Stack:** Kotlin, Compose Desktop, JDBC (mysql-connector y mariadb-java-client), JUnit 4, Testcontainers 2.0.5 (`mysql:8.4`, `mariadb:11.4`).

**Spec:** `docs/superpowers/specs/2026-10-16-modificar-tabla-mysql-design.md` (y `2026-10-15-modificar-tabla-design.md` para el diálogo).

## Global Constraints

- Solo **MySQL 8.0.19+ y MariaDB 10.5.2+**; sin ramas para versiones anteriores.
- Los tests de servidor usan **Testcontainers** con `mysql:8.4` y `mariadb:11.4`, con `assumeDocker()`; sin Docker se omiten, no fallan. Se conectan como `root` (el usuario del contenedor no puede crear bases).
- El modelo (`TableModel`) y el diálogo no dependen del motor: lo que depende de él va detrás de `Dialect`.
- Los textos de interfaz y mensajes están en español; los comentarios del código en inglés, una línea, solo el porqué (como el código existente).
- Cada tarea termina con sus tests pasando y se commitea sola. Los commits terminan con `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Ejecutar con Gradle: `./gradlew :drivers:test --tests '<clase>'` y `./gradlew :app:test --tests '<clase>'`.

## Review Focus

1. Renombrar una columna que usa un check: MySQL puede negarse; el generador quita y recrea el check con el nombre nuevo (Task 3 y Task 4).
2. Cambiar las columnas de la clave primaria en una tabla con `auto_increment`: quitar y añadir la clave va en una sola sentencia (Task 3 y Task 4).
3. Valores por defecto con comilla simple, barra invertida, expresión (`(uuid())`), `current_timestamp` y, en MariaDB, el texto `NULL` (Task 2).
4. El índice que MySQL crea solo para una clave foránea aparece como índice normal y no se pierde al releer (Task 2).
5. Fallo a mitad de la aplicación: para, cuenta las aplicadas y el diálogo recarga el modelo (Task 4 y Task 5).
6. Nombres con comilla inversa o palabra reservada (Task 3).

## File Structure

| Archivo | Cambio |
|---|---|
| `core/.../TableModel.kt` | `ColumnModel.generatedStored` |
| `drivers/.../dialect/ModifyCaps.kt` | nuevo: capacidades por dialecto |
| `drivers/.../dialect/Dialect.kt` | `val modifyCaps` |
| `drivers/.../dialect/TableChanges.kt` | nuevo: `changes` y `ColumnMoves`, extraídos de `PostgresAlter.kt` |
| `drivers/.../dialect/PostgresAlter.kt` | usa `TableChanges.kt` (sin cambio de comportamiento) |
| `drivers/.../dialect/MySqlDialect.kt` | `tableModel`, `alterTable`, `modifyCaps` |
| `drivers/.../dialect/MySqlAlter.kt` | nuevo: generador |
| `drivers/.../TableModelRules.kt` | `tableProblem(m, caps)` |
| `drivers/.../TableApply.kt` | `runStepwise` |
| `drivers/.../Alterations.kt` | `supportsModifyTable` |
| `app/.../ModifyDraft.kt` | `caps`, aviso en la vista previa |
| `app/.../ModifyDialog.kt` | campos según `caps` |
| `app/.../AppState.kt` | aplicar sin transacción y recargar |
| tests | `MySqlAlterTest`, `TableModelMySqlServerTest`, `TableAlterMySqlServerTest`, `ModifyMySqlUiTest`, ampliar `TableModelRulesTest` |

---

### Task 1: Capacidades y base común del generador

**Files:**
- Create: `drivers/src/main/kotlin/ksqlc/drivers/dialect/ModifyCaps.kt`
- Create: `drivers/src/main/kotlin/ksqlc/drivers/dialect/TableChanges.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/Dialect.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/PostgresAlter.kt`
- Modify: `core/src/main/kotlin/ksqlc/core/TableModel.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/TableAlterTest.kt` (existente, debe seguir pasando)

**Interfaces:**
- Produces: `data class ModifyCaps(...)`, `Dialect.modifyCaps`, `internal class Changes<T>`, `internal fun <T> changes(...)`, `internal class ColumnMoves(before, after)` con `kept`, `renamed`, `gone`, `mapped(List<String>)`, `mentionsGone(String?)`; `ColumnModel.generatedStored: Boolean = true`.

- [ ] **Step 1: Añadir `generatedStored` al modelo**

En `core/src/main/kotlin/ksqlc/core/TableModel.kt`, sustituir la clase `ColumnModel` por:

```kotlin
/** [type] and [default] are the text the server gives; [generated] is the expression of a generated column, [generatedStored] whether it is stored (else virtual). */
data class ColumnModel(
    val origin: String?, val name: String, val type: String,
    val notNull: Boolean = false, val default: String? = null, val comment: String? = null,
    val identity: Identity? = null, val generated: String? = null, val generatedStored: Boolean = true,
)
```

- [ ] **Step 2: Escribir `ModifyCaps.kt`**

```kotlin
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
```

- [ ] **Step 3: `Dialect.modifyCaps`**

En `Dialect.kt`, tras `alterTable`:

```kotlin
    /** What the Modify dialog offers for this engine. */
    val modifyCaps: ModifyCaps get() = ModifyCaps()
```

- [ ] **Step 4: Extraer `TableChanges.kt`**

```kotlin
package ksqlc.drivers.dialect

import ksqlc.core.TableModel

internal class Changes<T>(val dropped: List<T>, val created: List<T>, val renamed: List<Pair<T, T>>)

/** Pairs each [after] element with its [before] one by origin: a changed definition is dropped and created again, a new name alone is a rename. */
internal fun <T> changes(before: List<T>, after: List<T>, origin: (T) -> String?, name: (T) -> String, same: (T, T) -> Boolean): Changes<T> {
    val pairs = after.mapNotNull { x -> before.find { name(it) == origin(x) }?.let { it to x } }
    val changed = pairs.filter { (y, x) -> !same(y, x) }
    return Changes(
        dropped = before.filter { y -> after.none { origin(it) == name(y) } } + changed.map { it.first },
        created = after.filter { origin(it) == null } + changed.map { it.second },
        renamed = pairs.filter { (y, x) -> same(y, x) && name(y) != name(x) },
    )
}

/** How the columns moved between [before] and [after]: which kept their identity (and maybe a new name) and which are gone. */
internal class ColumnMoves(before: TableModel, after: TableModel) {
    val kept = after.columns.mapNotNull { a -> before.columns.find { it.name == a.origin }?.let { a to it } }
    val renamed = kept.filter { (a, b) -> a.name != b.name }.associate { (a, b) -> b.name to a.name }
    val gone = before.columns.map { it.name }.filter { n -> after.columns.none { it.origin == n } }

    /** A dropped column takes its constraints with it, so anything using it is dropped and created again even if a new column reuses the name. */
    fun mapped(l: List<String>) = l.map { if (it in gone) "\u0000$it" else renamed[it] ?: it }
    fun mentionsGone(text: String?) = text != null && gone.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
}
```

- [ ] **Step 5: `PostgresAlter.kt` usa lo extraído**

Quitar de `PostgresAlter.kt` la clase privada `Changes`, la función privada `changes` y su comentario KDoc (todo lo que va entre los `import` y `private fun identityOptions`). En `postgresAlterTable` sustituir el bloque

```kotlin
    val kept = after.columns.mapNotNull { a -> before.columns.find { it.name == a.origin }?.let { a to it } }
    val renamed = kept.filter { (a, b) -> a.name != b.name }.associate { (a, b) -> b.name to a.name }
    val gone = before.columns.map { it.name }.filter { n -> after.columns.none { it.origin == n } }
    // a dropped column takes its constraints with it, so anything using it must be dropped and created again even if a new column reuses the name
    fun mapped(l: List<String>) = l.map { if (it in gone) "\u0000$it" else renamed[it] ?: it }
    fun mentionsGone(text: String?) = text != null && gone.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
```

por

```kotlin
    val moves = ColumnMoves(before, after)
    val kept = moves.kept
    fun mapped(l: List<String>) = moves.mapped(l)
    fun mentionsGone(text: String?) = moves.mentionsGone(text)
```

(`renamed` y `gone` ya no se usan en el resto de la función; si el compilador marca alguno, usar `moves.renamed` / `moves.gone`.)

- [ ] **Step 6: Compilar y ejecutar los tests de PostgreSQL**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.TableAlterTest' --tests 'ksqlc.drivers.TableModelRulesTest'`
Expected: PASS (refactor sin cambio de comportamiento). Con Docker: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableAlterServerTest'` también PASS.

- [ ] **Step 7: Commit**

```bash
git add core drivers
git commit -m "Modificar tabla: capacidades por dialecto y emparejado común del generador

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Leer el modelo de una tabla de MySQL y MariaDB

**Files:**
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/MySqlDialect.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/server/TableModelMySqlServerTest.kt`

**Interfaces:**
- Consumes: `Dialect.tableModel(conn, o)`, `MetaDialect.query` (protected, extensión de `Connection`).
- Produces: `MySqlDialect.tableModel` y `MySqlDialect.modifyCaps`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

private val SETUP = listOf(
    "create database mt",
    "create table mt.parent(id int primary key)",
    """create table mt.t(
         id bigint unsigned not null auto_increment,
         name varchar(120) not null default 'it''s a \\ test' comment 'nombre',
         email varchar(100),
         parent_id int,
         price int default 0,
         qty int not null default 1,
         total decimal(12,2) generated always as (price * 2) stored,
         label varchar(20) generated always as (concat(name, '!')) virtual,
         primary key (id),
         unique key t_email_key (email),
         index t_name_idx (name desc, email),
         constraint t_parent_fk foreign key (parent_id) references mt.parent(id) on delete cascade on update set null,
         constraint t_price_ck check (price >= 0)
       ) comment = 'hola'""",
    "create table mt.skipped(a int, b varchar(50), c text, primary key (a), index pre_idx (b(10)), fulltext index ft_idx (c))",
)

/** Same expectations for MySQL and MariaDB: both go through [ksqlc.drivers.dialect.MySqlDialect]. */
abstract class TableModelMySqlBase {
    protected abstract val fx: ServerFixture
    private val t get() = DbObject(Namespace("mt", null), "t", DbObjectKind.TABLE)
    private fun model(name: String = "t") = Introspector(fx.ds, fx.password).use { it.tableModel(DbObject(Namespace("mt", null), name, DbObjectKind.TABLE))!! }

    @Test fun readsTableAndColumns() = model().let { m ->
        assertEquals("t", m.name); assertEquals("hola", m.comment)
        assertEquals(listOf("id", "name", "email", "parent_id", "price", "qty", "total", "label"), m.columns.map { it.name })
        assertEquals(m.columns.map { it.name }, m.columns.map { it.origin })
        val id = m.columns[0]
        assertTrue(id.type.startsWith("bigint") && id.type.endsWith("unsigned"), id.type); assertTrue(id.notNull)   // MariaDB keeps the display width: bigint(20) unsigned; assertNotNull(id.identity); assertNull(id.default)
        val name = m.columns[1]
        assertEquals("varchar(120)", name.type); assertTrue(name.notNull); assertEquals("nombre", name.comment)
        assertEquals("'it''s a \\\\ test'", name.default)       // a literal that can be pasted back into a statement
        assertFalse(m.columns[2].notNull); assertNull(m.columns[2].default)
        assertEquals("0", m.columns[4].default); assertEquals("1", m.columns[5].default)
        assertEquals(true, m.columns[6].generatedStored); assertNotNull(m.columns[6].generated); assertNull(m.columns[6].default)
        assertEquals(false, m.columns[7].generatedStored)
    }

    @Test fun readsKeysIndexesForeignKeysAndChecks() = model().let { m ->
        assertEquals(listOf("PRIMARY", "t_email_key"), m.keys.map { it.name })
        assertTrue(m.keys[0].primary); assertEquals(listOf("id"), m.keys[0].columns)
        assertFalse(m.keys[1].primary); assertEquals(listOf("email"), m.keys[1].columns)
        // the index MySQL makes for the foreign key is an ordinary index
        assertEquals(setOf("t_name_idx", "t_parent_fk"), m.indexes.map { it.name }.toSet())
        val ix = m.indexes.single { it.name == "t_name_idx" }
        assertEquals("btree", ix.method)
        assertEquals(listOf("name" to "DESC", "email" to ""), ix.columns.map { it.name to it.order })
        val fk = m.foreignKeys.single()
        assertEquals("t_parent_fk", fk.name); assertEquals(listOf("parent_id"), fk.columns)
        assertEquals(Namespace("mt", null), fk.refNs); assertEquals("parent", fk.refTable); assertEquals(listOf("id"), fk.refColumns)
        assertEquals("CASCADE", fk.onDelete); assertEquals("SET NULL", fk.onUpdate)
        val ck = m.checks.single()
        assertEquals("t_price_ck", ck.name); assertTrue(ck.expression.contains("price"), ck.expression)
    }

    @Test fun prefixAndFulltextIndexesAreLeftOut() = model("skipped").let { m ->
        assertEquals(emptyList(), m.indexes.map { it.name })
        assertEquals(listOf("PRIMARY"), m.keys.map { it.name })
    }

    @Test fun aMissingTableIsAnError() {
        val e = assertFails { model("nope") }
        assertTrue(e.message!!.contains("nope"))
    }
}

class TableModelMySqlTest : TableModelMySqlBase() {
    override val fx get() = Companion.fx
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class TableModelMariaDbTest : TableModelMySqlBase() {
    override val fx get() = Companion.fx
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
```

- [ ] **Step 2: Ejecutarlo y ver que falla**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableModelMySqlTest' --tests 'ksqlc.drivers.server.TableModelMariaDbTest'`
Expected: FAIL (`tableModel` devuelve null → `NullPointerException` en `!!`).

- [ ] **Step 3: Implementar la lectura**

En `MySqlDialect.kt` añadir los imports `java.sql.ResultSet` si hace falta y, dentro del objeto:

```kotlin
    override val modifyCaps = ModifyCaps(
        atomic = false, deferrable = false, indexExtras = false, identityOptions = false, singleIdentity = true,
        virtualColumns = true, refInCatalog = true, indexMethods = listOf("btree", "hash"),
    )

    override fun tableModel(conn: Connection, o: DbObject): TableModel {
        val db = o.ns.catalog
        val mariaDb = conn.metaData.databaseProductVersion.contains("MariaDB", ignoreCase = true)
        val comment = conn.query("select table_comment from information_schema.tables where table_schema = ? and table_name = ?", db, o.name) { it.getString(1) }
            .firstOrNull() ?: error("${o.name} no existe")

        val columns = conn.query(
            """select column_name, column_type, is_nullable, column_default, extra, generation_expression, column_comment, data_type
               from information_schema.columns where table_schema = ? and table_name = ? order by ordinal_position""",
            db, o.name,
        ) { rs ->
            val extra = rs.getString(5).orEmpty().lowercase()
            val generated = "generated" in extra && "default_generated" !in extra
            ColumnModel(
                rs.getString(1), rs.getString(1), rs.getString(2), rs.getString(3) == "NO",
                if (generated) null else defaultText(rs.getString(4), extra, rs.getString(8), mariaDb, rs.getString(3) == "YES"),
                rs.getString(7)?.ifEmpty { null },
                if ("auto_increment" in extra) Identity() else null,
                if (generated) rs.getString(6) else null,
                "virtual" !in extra,
            )
        }

        // the `expression` column of `statistics` exists from MySQL 8.0.13 only
        val hasExpression = conn.query(
            "select 1 from information_schema.columns where upper(table_schema) = 'INFORMATION_SCHEMA' and upper(table_name) = 'STATISTICS' and upper(column_name) = 'EXPRESSION'",
        ) { 1 }.isNotEmpty()
        class Part(val index: String, val nonUnique: Boolean, val name: String?, val desc: Boolean, val type: String, val expression: String?, val prefix: Boolean)
        val parts = conn.query(
            "select index_name, non_unique, column_name, collation, index_type, sub_part, " + (if (hasExpression) "expression" else "null") +
                " from information_schema.statistics where table_schema = ? and table_name = ? order by index_name, seq_in_index",
            db, o.name,
        ) { rs -> Part(rs.getString(1), rs.getInt(2) != 0, rs.getString(3), rs.getString(4) == "D", rs.getString(5).uppercase(), rs.getString(7), rs.getObject(6) != null) }
        // fulltext and spatial indexes and prefix parts are not modeled: leaving them out means they are never dropped or recreated
        val usable = parts.groupBy { it.index }.filterValues { ps -> ps.none { it.prefix } && ps.first().type in setOf("BTREE", "HASH") }
        // a unique key with an expression part is left out too: dropping the expression would change what it means
        val keys = usable.filterValues { !it.first().nonUnique && it.all { p -> p.name != null } }.map { (name, ps) ->
            KeyModel(name, name, name == "PRIMARY", ps.map { it.name!! })
        }.sortedWith(compareBy({ !it.primary }, { it.name }))
        val indexes = usable.filterValues { it.first().nonUnique }.map { (name, ps) ->
            IndexModel(name, name, false, ps.first().type.lowercase(),
                ps.map { if (it.name != null) IndexColumn(it.name, order = if (it.desc) "DESC" else "") else IndexColumn(it.expression.orEmpty(), expression = true, order = if (it.desc) "DESC" else "") })
        }.sortedBy { it.name }

        val foreignKeys = conn.query(
            """select k.constraint_name, k.column_name, k.referenced_table_schema, k.referenced_table_name, k.referenced_column_name, r.update_rule, r.delete_rule
               from information_schema.key_column_usage k
               join information_schema.referential_constraints r
                 on r.constraint_schema = k.constraint_schema and r.constraint_name = k.constraint_name and r.table_name = k.table_name
               where k.table_schema = ? and k.table_name = ? and k.referenced_table_name is not null
               order by k.constraint_name, k.ordinal_position""",
            db, o.name,
        ) { rs -> arrayOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) }
            .groupBy { it[0] }.map { (name, rows) ->
                ForeignKeyModel(name, name, rows.map { it[1] }, Namespace(rows.first()[2], null), rows.first()[3], rows.map { it[4] }, rows.first()[6], rows.first()[5])
            }

        val checks = conn.query(
            """select cc.constraint_name, cc.check_clause from information_schema.check_constraints cc
               join information_schema.table_constraints tc on tc.constraint_schema = cc.constraint_schema and tc.constraint_name = cc.constraint_name
               where tc.table_schema = ? and tc.table_name = ? and tc.constraint_type = 'CHECK' order by cc.constraint_name""",
            db, o.name,
        ) { CheckModel(it.getString(1), it.getString(1), it.getString(2)) }

        return TableModel(o.name, comment.ifEmpty { null }, columns, keys, foreignKeys, indexes, checks)
    }

    /**
     * The default as SQL text that can go back into a statement. MariaDB already gives it so (`'x'`, `current_timestamp()`, and the word `NULL` for none);
     * MySQL gives a string without quotes, and an expression default flagged `DEFAULT_GENERATED`.
     */
    private fun defaultText(raw: String?, extra: String, dataType: String, mariaDb: Boolean, nullable: Boolean): String? {
        if (raw == null) return null
        if (mariaDb) return if (raw.equals("NULL", ignoreCase = true) && nullable) null else raw
        if ("default_generated" in extra) return if (raw.startsWith("CURRENT_TIMESTAMP", ignoreCase = true)) raw else "($raw)"
        val numeric = dataType.lowercase() in setOf("tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "numeric", "float", "double")
        return if (numeric) raw else "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'"
    }
```

Añadir a los imports de `MySqlDialect.kt`: nada nuevo (`ksqlc.core.*` ya está).

- [ ] **Step 4: Ejecutar el test**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableModelMySqlTest' --tests 'ksqlc.drivers.server.TableModelMariaDbTest'`
Expected: PASS en ambos. Si un valor difiere entre servidores (comillas del default, texto de `check_clause`, nombre del tipo de índice), ajustar `defaultText` o la consulta hasta que ambos pasen: el servidor real manda sobre lo que el plan supone.

- [ ] **Step 5: Commit**

```bash
git add drivers
git commit -m "Modificar tabla: leer el modelo de una tabla de MySQL y MariaDB

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Generador de ALTER para MySQL y MariaDB

**Files:**
- Create: `drivers/src/main/kotlin/ksqlc/drivers/dialect/MySqlAlter.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/MySqlDialect.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/TableModelRules.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/MySqlAlterTest.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/TableModelRulesTest.kt`

**Interfaces:**
- Consumes: `changes`, `ColumnMoves` (Task 1), `ModifyCaps`.
- Produces: `MySqlDialect.alterTable(o, before, after)`; `tableProblem(m: TableModel, caps: ModifyCaps = ModifyCaps()): String?`.

- [ ] **Step 1: Escribir los tests del generador (fallan)**

```kotlin
package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.MySqlDialect
import kotlin.test.*

class MySqlAlterTest {
    private val tbl = DbObject(Namespace("app", null), "t", DbObjectKind.TABLE)
    private val q = "`app`.`t`"
    private fun col(name: String, type: String = "int", notNull: Boolean = false, default: String? = null) = ColumnModel(name, name, type, notNull, default)
    private val base = TableModel(
        "t", null,
        columns = listOf(col("id", "bigint", true).copy(identity = Identity()), col("name", "varchar(50)"), col("price", "int")),
        keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("id")), KeyModel("t_name_key", "t_name_key", false, listOf("name"))),
        indexes = listOf(IndexModel("t_price_idx", "t_price_idx", false, "btree", listOf(IndexColumn("price")))),
        checks = listOf(CheckModel("t_price_ck", "t_price_ck", "(`price` > 0)")),
    )
    private fun diff(after: TableModel, before: TableModel = base) = MySqlDialect.alterTable(tbl, before, after)

    @Test fun noChangesNoStatements() = assertEquals(emptyList(), diff(base))

    @Test fun renamingAColumnKeepsKeysAndIndexesButRecreatesTheCheckWithTheNewName() {
        val after = base.copy(
            columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(name = "cost")),
            indexes = listOf(base.indexes[0].copy(columns = listOf(IndexColumn("cost")))),
            checks = listOf(base.checks[0]),
        )
        assertEquals(listOf(
            "alter table $q drop constraint `t_price_ck`",
            "alter table $q rename column `price` to `cost`",
            "alter table $q add constraint `t_price_ck` check ((`cost` > 0))",
        ), diff(after))
    }

    @Test fun addingAColumnWithEverything() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "note", "varchar(20)", true, "'x'", "it's"))
        assertEquals(listOf("alter table $q add column `note` varchar(20) not null default 'x' comment 'it''s'"), diff(after))
    }

    @Test fun commentsEscapeBackslashesAndQuotes() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "c", "int", comment = "a\\b'c"))
        assertEquals(listOf("alter table $q add column `c` int null comment 'a\\\\b''c'"), diff(after))
    }

    @Test fun changingTypeNotNullDefaultAndCommentModifiesTheWholeDefinition() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(type = "varchar(80)", notNull = true, default = "'a'", comment = "n"), base.columns[2]))
        assertEquals(listOf("alter table $q modify column `name` varchar(80) not null default 'a' comment 'n'"), diff(after))
    }

    @Test fun droppingAColumnDropsTheIndexAndTheCheckThatUseIt() {
        val after = base.copy(columns = base.columns.take(2), indexes = emptyList(), checks = emptyList())
        assertEquals(listOf(
            "alter table $q drop constraint `t_price_ck`",
            "alter table $q drop index `t_price_idx`",
            "alter table $q drop column `price`",
        ), diff(after))
    }

    @Test fun keysAndIndexesRenameInPlaceAndTheyAreRecreatedWhenTheirContentChanges() {
        val renamed = base.copy(keys = listOf(base.keys[0], base.keys[1].copy(name = "uq_name")), indexes = listOf(base.indexes[0].copy(name = "idx_price")))
        assertEquals(listOf(
            "alter table $q rename index `t_name_key` to `uq_name`",
            "alter table $q rename index `t_price_idx` to `idx_price`",
        ), diff(renamed))
        val changed = base.copy(indexes = listOf(base.indexes[0].copy(method = "hash", columns = listOf(IndexColumn("price", order = "DESC"), IndexColumn("name")))))
        assertEquals(listOf(
            "alter table $q drop index `t_price_idx`",
            "create index `t_price_idx` using hash on $q (`price` desc, `name`)",
        ), diff(changed))
    }

    @Test fun primaryKeyRenameDoesNothingAndChangingItsColumnsIsOneStatement() {
        assertEquals(emptyList(), diff(base.copy(keys = listOf(base.keys[0].copy(name = "pk"), base.keys[1]))))
        val after = base.copy(keys = listOf(base.keys[0].copy(columns = listOf("id", "name")), base.keys[1]))
        assertEquals(listOf("alter table $q drop primary key, add primary key (`id`, `name`)"), diff(after))
    }

    @Test fun uniqueKeysIndexExpressionsAndChecks() {
        val after = base.copy(
            keys = base.keys + KeyModel(null, "t_price_key", false, listOf("price", "name")),
            indexes = base.indexes + IndexModel(null, "t_lower", false, "btree", listOf(IndexColumn("lower(`name`)", expression = true))),
            checks = base.checks + CheckModel(null, "t_name_ck", "`name` <> ''"),
        )
        assertEquals(listOf(
            "alter table $q add constraint `t_price_key` unique (`price`, `name`)",
            "alter table $q add constraint `t_name_ck` check (`name` <> '')",
            "create index `t_lower` using btree on $q ((lower(`name`)))",
        ), diff(after))
    }

    @Test fun foreignKeysAreDroppedAndCreatedAndDefaultToTheTablesOwnDatabase() {
        val fk = ForeignKeyModel(null, "t_ref_fk", listOf("price"), Namespace(null, null), "parent", listOf("id"), onDelete = "CASCADE", onUpdate = "SET NULL")
        val withFk = diff(base.copy(foreignKeys = listOf(fk)))
        assertEquals(listOf("alter table $q add constraint `t_ref_fk` foreign key (`price`) references `app`.`parent` (`id`) on delete CASCADE on update SET NULL"), withFk)
        val before = base.copy(foreignKeys = listOf(fk.copy(origin = "t_ref_fk", refNs = Namespace("app", null))))
        assertEquals(listOf("alter table $q drop foreign key `t_ref_fk`"), diff(base, before))
        val other = fk.copy(refNs = Namespace("other", null))
        assertTrue(diff(base.copy(foreignKeys = listOf(other))).single().contains("references `other`.`parent`"))
    }

    @Test fun auto_incrementIsAddedAfterTheKeyThatSupportsIt() {
        val before = base.copy(columns = listOf(col("id", "bigint", true), base.columns[1], base.columns[2]), keys = emptyList(), indexes = emptyList(), checks = emptyList())
        val after = before.copy(columns = listOf(before.columns[0].copy(identity = Identity()), before.columns[1], before.columns[2]), keys = listOf(KeyModel(null, "PRIMARY", true, listOf("id"))))
        assertEquals(listOf(
            "alter table $q add primary key (`id`)",
            "alter table $q modify column `id` bigint not null auto_increment",
        ), diff(after, before))
        // taking it away
        assertEquals(listOf("alter table $q modify column `id` bigint not null"), diff(base.copy(columns = listOf(base.columns[0].copy(identity = null)) + base.columns.drop(1))))
    }

    @Test fun generatedColumnsAreOnlyCreatedAndKeepStoredOrVirtual() {
        val stored = ColumnModel(null, "g1", "int", generated = "price * 2")
        val virtual = ColumnModel(null, "g2", "int", generated = "price * 3", generatedStored = false)
        assertEquals(listOf(
            "alter table $q add column `g1` int generated always as (price * 2) stored null",
            "alter table $q add column `g2` int generated always as (price * 3) virtual null",
        ), diff(base.copy(columns = base.columns + stored + virtual)))
    }

    @Test fun commentAndRenameOfTheTableGoLast() {
        val after = base.copy(name = "t2", comment = "it's")
        assertEquals(listOf("alter table $q comment = 'it''s'", "alter table $q rename to `t2`"), diff(after))
    }

    @Test fun namesWithBackticksAndReservedWordsAreQuoted() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "we`ird order", "int"))
        assertEquals(listOf("alter table $q add column `we``ird order` int null"), diff(after))
    }
}
```

- [ ] **Step 2: Escribir los tests de las reglas (fallan)**

Añadir a `TableModelRulesTest.kt` (importar `ksqlc.drivers.dialect.ModifyCaps`):

```kotlin
    @Test fun withASingleIdentityThereCanBeOnlyOneAndItMustLeadAKey() {
        val caps = ModifyCaps(singleIdentity = true)
        val id = ColumnModel("a", "a", "int", true, identity = Identity())
        val ok = TableModel("t", null, columns = listOf(id, ColumnModel("b", "b", "int")), keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("a"))))
        assertNull(tableProblem(ok, caps))
        assertTrue(tableProblem(ok.copy(keys = emptyList()), caps)!!.contains("clave"))
        assertTrue(tableProblem(ok.copy(keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("b", "a")))), caps)!!.contains("clave"))
        assertTrue(tableProblem(ok.copy(columns = ok.columns + id.copy(name = "c", origin = "c")), caps)!!.contains("una"))
        assertNull(tableProblem(ok.copy(keys = emptyList(), columns = ok.columns), ModifyCaps()))     // PostgreSQL: no such rule
    }
```

(Si `TableModelRulesTest` no importa `ksqlc.core.*`, añadirlo.)

- [ ] **Step 3: Ejecutarlos y ver que fallan**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.MySqlAlterTest' --tests 'ksqlc.drivers.TableModelRulesTest'`
Expected: FAIL (no compila: `tableProblem` no acepta `caps`; `alterTable` lanza «Este motor no puede modificar tablas»).

- [ ] **Step 4: Escribir el generador**

`drivers/src/main/kotlin/ksqlc/drivers/dialect/MySqlAlter.kt`:

```kotlin
package ksqlc.drivers.dialect

import ksqlc.core.*

internal fun mysqlAlterTable(d: Dialect, o: DbObject, before: TableModel, after: TableModel): List<String> {
    val q = d.qualified(o)
    fun id(n: String) = d.quote(n)
    fun ids(l: List<String>) = l.joinToString(", ") { d.quote(it) }
    // backslash is an escape in a MySQL string unless NO_BACKSLASH_ESCAPES is on
    fun lit(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"
    fun alter(vararg clauses: String) = "alter table $q " + clauses.joinToString(", ")
    val out = ArrayList<String>()

    /** The whole definition of a column, which is what `add column` and `modify column` both take. [auto] leaves `auto_increment` for later when its key is not there yet. */
    fun definition(c: ColumnModel, auto: Boolean): String = buildString {
        append(id(c.name), " ", c.type)
        val gen = c.generated
        if (gen != null) append(" generated always as ($gen) ", if (c.generatedStored) "stored" else "virtual")
        append(if (c.notNull) " not null" else " null")
        if (gen == null) {
            c.default?.let { append(" default ", it) }
            if (auto && c.identity != null) append(" auto_increment")
        }
        c.comment?.let { append(" comment ", lit(it)) }
    }

    val moves = ColumnMoves(before, after)
    val kept = moves.kept
    fun mapped(l: List<String>) = moves.mapped(l)
    // the server updates keys, indexes and foreign keys on a rename but a check keeps its text, so a check that uses a renamed column is written again
    fun renamedIn(e: String) = moves.renamed.entries.fold(e) { s, (old, new) -> s.replace("`$old`", "`$new`") }
    fun touched(e: String) = renamedIn(e) != e || moves.mentionsGone(e)

    val keys = changes(before.keys, after.keys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val foreignKeys = changes(before.foreignKeys, after.foreignKeys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val checks = changes(before.checks, after.checks, { it.origin }, { it.name }) { y, x -> y.expression == x.expression && !touched(y.expression) }
    val checksCreated = checks.created.map { x ->
        val y = before.checks.find { it.name == x.origin }
        if (y != null && y.expression == x.expression) x.copy(expression = renamedIn(x.expression)) else x
    }
    val indexes = changes(before.indexes, after.indexes, { it.origin }, { it.name }) { y, x ->
        val columns = y.columns.map { if (it.expression) it else it.copy(name = mapped(listOf(it.name)).single()) }
        y.columns.none { it.expression && moves.mentionsGone(it.name) } &&
            y.copy(origin = null, name = "", columns = columns) == x.copy(origin = null, name = "")
    }

    // changing the primary key's columns is one statement: the table may have an auto_increment column that needs it at every moment
    val pkChange = keys.dropped.any { it.primary } && keys.created.any { it.primary }

    // 1. what goes away
    foreignKeys.dropped.forEach { out += alter("drop foreign key ${id(it.name)}") }
    checks.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    keys.dropped.filter { !pkChange || !it.primary }.forEach { out += alter(if (it.primary) "drop primary key" else "drop index ${id(it.name)}") }
    indexes.dropped.forEach { out += alter("drop index ${id(it.name)}") }

    // 2. columns; `auto_increment` waits until its key exists
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }.forEach { out += alter("drop column ${id(it.name)}") }
    for ((a, b) in kept) if (a.name != b.name) out += alter("rename column ${id(b.name)} to ${id(a.name)}")
    for (c in after.columns.filter { it.origin == null }) out += alter("add column ${definition(c, auto = false)}")
    for ((a, b) in kept) {
        val lostAuto = a.identity == null && b.identity != null
        if (a.generated == null && (a.type != b.type || a.notNull != b.notNull || a.default != b.default || a.comment != b.comment || lostAuto))
            out += alter("modify column ${definition(a, auto = a.identity != null && b.identity != null)}")
    }

    // 3. what is new or only renamed
    keys.renamed.filter { !it.second.primary }.forEach { (y, x) -> out += alter("rename index ${id(y.name)} to ${id(x.name)}") }
    indexes.renamed.forEach { (y, x) -> out += alter("rename index ${id(y.name)} to ${id(x.name)}") }
    for (k in keys.created) {
        val add = if (k.primary) "add primary key (${ids(k.columns)})" else "add constraint ${id(k.name)} unique (${ids(k.columns)})"
        out += alter(*(if (k.primary && pkChange) arrayOf("drop primary key", add) else arrayOf(add)))
    }
    checksCreated.forEach { out += alter("add constraint ${id(it.name)} check (${it.expression})") }
    for (i in indexes.created) {
        val columns = i.columns.joinToString(", ") { c ->
            (if (c.expression) "(${c.name})" else id(c.name)) + when (c.order) { "DESC" -> " desc"; "ASC" -> " asc"; else -> "" }
        }
        out += "create ${if (i.unique) "unique " else ""}index ${id(i.name)} using ${i.method} on $q ($columns)"
    }
    for (f in foreignKeys.created) {
        val ref = d.qualified(DbObject(if (f.refNs.catalog == null) o.ns else f.refNs, f.refTable, DbObjectKind.TABLE))
        out += alter("add constraint ${id(f.name)} foreign key (${ids(f.columns)}) references $ref (${ids(f.refColumns)}) on delete ${f.onDelete} on update ${f.onUpdate}")
    }
    // a column that is auto_increment now but was not, once the key that supports it is in place
    for (a in after.columns.filter { it.identity != null && it.generated == null }) {
        val b = kept.find { it.first === a }?.second
        if (b == null || b.identity == null) out += alter("modify column ${definition(a, auto = true)}")
    }

    // 4. the table itself, last so everything above uses the name it has now
    if (after.comment != before.comment) out += alter("comment = ${lit(after.comment.orEmpty())}")
    if (after.name != before.name) out += alter("rename to ${id(after.name)}")
    return out
}
```

Y en `MySqlDialect.kt`:

```kotlin
    override fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = mysqlAlterTable(this, o, before, after)
```

- [ ] **Step 5: `tableProblem` con capacidades**

En `TableModelRules.kt` añadir el import `ksqlc.drivers.dialect.ModifyCaps` y cambiar la firma y el final de la función:

```kotlin
fun tableProblem(m: TableModel, caps: ModifyCaps = ModifyCaps()): String? {
```

y antes de `return null`:

```kotlin
    if (caps.singleIdentity) {
        val autos = m.columns.filter { it.identity != null }
        if (autos.size > 1) return "Solo puede haber una columna auto_increment"
        autos.singleOrNull()?.let { c ->
            val leads = m.keys.any { it.columns.firstOrNull() == c.name } || m.indexes.any { it.columns.firstOrNull()?.name == c.name }
            if (!leads) return "La columna ${c.name} es auto_increment y tiene que ser la primera de una clave o de un índice"
        }
    }
    return null
```

(`return null` original se sustituye por este bloque.)

- [ ] **Step 6: Ejecutar los tests**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.MySqlAlterTest' --tests 'ksqlc.drivers.TableModelRulesTest' --tests 'ksqlc.drivers.TableAlterTest'`
Expected: PASS. Si un caso de `MySqlAlterTest` difiere solo en el orden de las sentencias, corregir el generador (no el test) si el orden del spec lo exige; el orden es: quitar → columnas → crear/renombrar → `auto_increment` → comentario → renombrar tabla.

- [ ] **Step 7: Commit**

```bash
git add drivers
git commit -m "Modificar tabla: generador de ALTER para MySQL y MariaDB y regla de auto_increment

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Aplicar sentencia a sentencia y probarlo contra servidores

**Files:**
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/TableApply.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/server/TableAlterMySqlServerTest.kt`

**Interfaces:**
- Consumes: `MySqlDialect.alterTable`, `tableModel` (Tasks 2‑3).
- Produces: `class ApplyResult(val applied: Int, val error: String?)`, `fun runStepwise(ds, password, statements, prelude = emptyList()): ApplyResult`.

- [ ] **Step 1: Escribir el test (falla)**

```kotlin
package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.runStepwise
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

private val SETUP = listOf(
    "create database at",
    "create table at.parent(id int primary key)",
    """create table at.t(
         id int not null auto_increment, name varchar(50) not null default 'x', parent_id int, price int,
         primary key (id), unique key t_name_key (name), index t_price_idx (price),
         constraint t_parent_fk foreign key (parent_id) references at.parent(id),
         constraint t_price_ck check (price > 0))""",
    "insert into at.parent values (1)",
    "insert into at.t(name, parent_id, price) values ('a', 1, 5), ('b', 1, 6)",
)

abstract class TableAlterMySqlBase(private val kind: DbKind) {
    protected abstract val fx: ServerFixture
    private fun obj(name: String) = DbObject(Namespace("at", null), name, DbObjectKind.TABLE)
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun model(name: String) = intro().use { it.tableModel(obj(name))!! }
    private fun statements(name: String, f: (TableModel) -> TableModel): List<String> {
        val before = model(name)
        return Dialect.of(kind).alterTable(obj(name), before, f(before))
    }
    @BeforeTest fun fresh() { runStepwise(fx.ds, fx.password, listOf("drop table if exists at.t2", "create table at.t2(a int, b int)")) }

    @Test fun aLoadedModelProducesNoStatements() {
        for (t in listOf("t", "parent", "t2")) assertEquals(emptyList(), statements(t) { it }, t)
    }

    @Test fun appliesAColumnAndConstraintChangeAndReadsBackWithNoDifference() {
        val sqlList = statements("t2") { m ->
            m.copy(
                comment = "tabla t2",
                columns = listOf(
                    m.columns[0].copy(name = "uid", notNull = true, type = "bigint"),
                    m.columns[1],
                    ColumnModel(null, "label", "varchar(30)", default = "'n'", comment = "etiqueta"),
                    ColumnModel(null, "ref", "int"),
                ),
                keys = listOf(KeyModel(null, "PRIMARY", true, listOf("uid"))),
                indexes = listOf(IndexModel(null, "t2_label_idx", false, "btree", listOf(IndexColumn("label", order = "DESC")))),
                checks = listOf(CheckModel(null, "t2_uid_ck", "`uid` > 0")),
                foreignKeys = listOf(ForeignKeyModel(null, "t2_ref_fk", listOf("ref"), Namespace("at", null), "parent", listOf("id"), onDelete = "SET NULL")),
            )
        }
        val r = runStepwise(fx.ds, fx.password, sqlList)
        assertNull(r.error, sqlList.joinToString("\n")); assertEquals(sqlList.size, r.applied)
        val m = model("t2")
        assertEquals("tabla t2", m.comment)
        assertEquals(listOf("uid", "b", "label", "ref"), m.columns.map { it.name })
        assertEquals("bigint", m.columns[0].type); assertTrue(m.columns[0].notNull)
        assertEquals("etiqueta", m.columns[2].comment); assertEquals("'n'", m.columns[2].default)
        assertEquals(listOf("PRIMARY"), m.keys.map { it.name })
        assertEquals("SET NULL", m.foreignKeys.single().onDelete)
        assertEquals("DESC", m.indexes.single { it.name == "t2_label_idx" }.columns[0].order)
        assertEquals(emptyList(), statements("t2") { it })      // what was applied reads back with no difference
    }

    @Test fun renamingAColumnKeepsDataIndexAndCheck() {
        val sqlList = statements("t") { m ->
            m.copy(
                columns = m.columns.map { if (it.name == "price") it.copy(name = "cost") else it },
                indexes = m.indexes.map { ix -> ix.copy(columns = ix.columns.map { c -> if (c.name == "price") c.copy(name = "cost") else c }) },
            )
        }
        val r = runStepwise(fx.ds, fx.password, sqlList)
        assertNull(r.error, sqlList.joinToString("\n"))
        val m = model("t")
        assertEquals(listOf("id", "name", "parent_id", "cost"), m.columns.map { it.name })
        assertEquals(listOf("cost"), m.indexes.single { it.name == "t_price_idx" }.columns.map { it.name })
        assertTrue(m.checks.single().expression.contains("cost"), m.checks.single().expression)
    }

    @Test fun changingThePrimaryKeyOfATableWithAutoIncrementWorks() {
        runStepwise(fx.ds, fx.password, listOf("drop table if exists at.pk", "create table at.pk(id int not null auto_increment, g int not null default 0, primary key (id), key pk_g (g))"))
        val sqlList = statements("pk") { m -> m.copy(keys = listOf(m.keys[0].copy(columns = listOf("id", "g")))) }
        assertEquals(1, sqlList.size, sqlList.joinToString("\n"))
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error)
        assertEquals(listOf("id", "g"), model("pk").keys.single().columns)
    }

    @Test fun addingAutoIncrementAfterTheKey() {
        val sqlList = statements("t2") { m ->
            m.copy(columns = listOf(m.columns[0].copy(notNull = true, identity = Identity()), m.columns[1]), keys = listOf(KeyModel(null, "PRIMARY", true, listOf("a"))))
        }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        assertNotNull(model("t2").columns[0].identity)
    }

    @Test fun aFailureInTheMiddleStopsAndLeavesWhatRanApplied() {
        val r = runStepwise(fx.ds, fx.password, listOf(
            "alter table at.t2 add column c1 int",
            "alter table at.t2 add column c1 int",         // duplicate: fails
            "alter table at.t2 add column c2 int",
        ))
        assertEquals(1, r.applied); assertNotNull(r.error); assertTrue(r.error!!.contains("c1"))
        assertEquals(listOf("a", "b", "c1"), model("t2").columns.map { it.name })      // the first stayed, the third never ran
    }

    @Test fun renamingTheTable() {
        val sqlList = statements("t2") { it.copy(name = "t3") }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error)
        assertEquals(listOf("a", "b"), model("t3").columns.map { it.name })
        runStepwise(fx.ds, fx.password, listOf("drop table at.t3"))
    }
}

class TableAlterMySqlTest : TableAlterMySqlBase(DbKind.MYSQL) {
    override val fx get() = Companion.fx
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class TableAlterMariaDbTest : TableAlterMySqlBase(DbKind.MARIADB) {
    override val fx get() = Companion.fx
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
```

- [ ] **Step 2: Ejecutarlo y ver que falla**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableAlterMySqlTest'`
Expected: FAIL (no compila: `runStepwise` no existe).

- [ ] **Step 3: Escribir `runStepwise`**

En `TableApply.kt` añadir:

```kotlin
/** [applied] statements ran before [error] (null when all went through); the engine had already committed each one. */
class ApplyResult(val applied: Int, val error: String?)

/** Runs [statements] one by one, each committed on its own, stopping at the first that fails; [prelude] runs first and is not counted. */
fun runStepwise(ds: DataSource, password: String?, statements: List<String>, prelude: List<String> = emptyList()): ApplyResult =
    Session(ds, password, autoCommit = true).use { s ->
        for (sql in prelude) s.runScript(listOf(sql))
        for ((i, sql) in statements.withIndex()) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) return ApplyResult(i, "${r.message}\n$sql")
        }
        ApplyResult(statements.size, null)
    }
```

- [ ] **Step 4: Ejecutar los tests de servidor**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableAlterMySqlTest' --tests 'ksqlc.drivers.server.TableAlterMariaDbTest'`
Expected: PASS en ambos. Los casos que pueden obligar a ajustar `MySqlAlter.kt` (el servidor manda): renombrar una columna usada por un check (`renamingAColumnKeepsDataIndexAndCheck`), cambiar la clave primaria con `auto_increment` (una sola sentencia) y añadir `auto_increment` tras la clave. Si un servidor rechaza una sentencia, ajustar el generador y su test unitario de Task 3 a lo que acepta ese servidor, sin dejar un caso que pase en uno y falle en el otro.

- [ ] **Step 5: Commit**

```bash
git add drivers
git commit -m "Modificar tabla: aplicar sin transacción y pruebas contra MySQL 8.4 y MariaDB 11.4

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Diálogo, aplicación en el estado y menú

**Files:**
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/Alterations.kt`
- Modify: `app/src/main/kotlin/ksqlc/app/ModifyDraft.kt`
- Modify: `app/src/main/kotlin/ksqlc/app/ModifyDialog.kt`
- Modify: `app/src/main/kotlin/ksqlc/app/AppState.kt`
- Test: `app/src/test/kotlin/ksqlc/app/ModifyDraftTest.kt`
- Test: `app/src/test/kotlin/ksqlc/app/ModifyMySqlUiTest.kt`

**Interfaces:**
- Consumes: `ModifyCaps`, `runStepwise`, `tableProblem(m, caps)`.
- Produces: `ModifyDraft.caps`, `ModifyDraft.notice`, `AppState.reloadModify()`.

- [ ] **Step 1: Test de `ModifyDraft` (falla)**

Añadir a `ModifyDraftTest.kt`:

```kotlin
    @Test fun anEngineWithoutTransactionalDdlWarnsInTheNoticeAndChecksAutoIncrement() {
        val d = ModifyDraft(DbObject(Namespace("app", null), "t", DbObjectKind.TABLE), TableModel("t", null, columns = listOf(ColumnModel("a", "a", "int", true), ColumnModel("b", "b", "int"))), Dialect.of(DbKind.MYSQL))
        assertTrue(d.notice!!.contains("cada sentencia"))
        assertNull(ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES)).notice)
        d.updateColumn(0) { it.copy(identity = Identity()) }
        assertTrue(d.problem!!.contains("auto_increment"))
        assertEquals(emptyList(), d.statements)
    }
```

Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyDraftTest'` → FAIL (no compila: `notice`).

- [ ] **Step 2: `ModifyDraft`**

En `ModifyDraft.kt`: reemplazar la línea de `problem` por

```kotlin
    val caps get() = dialect.modifyCaps
    val problem: String? get() = tableProblem(model, caps)

    /** What the preview says before the SQL when applying is not all or nothing. */
    val notice: String? get() = if (caps.atomic) null else "MySQL y MariaDB confirman cada sentencia: si una falla, las anteriores quedan aplicadas."
```

Y `import ksqlc.drivers.tableProblem` ya existe. Run del test → PASS.

- [ ] **Step 3: Vista previa y campos del diálogo según `caps`**

En `ModifyDialog.kt`, en `ModifyDialog` cambiar la línea de `preview`:

```kotlin
    val body = draft.problem ?: if (sql.isEmpty()) "-- sin cambios" else sql.joinToString("\n") { "$it;" }
    val preview = if (draft.problem == null && sql.isNotEmpty()) draft.notice?.let { "-- $it\n$body" } ?: body else body
```

En `ModifyForm` (usa `val caps = draft.caps` al principio):
- Claves: envolver las dos casillas «Diferible» e «Inicialmente diferida» en `if (caps.deferrable) { … }`.
- Claves foráneas: la casilla «Diferible» en `if (caps.deferrable)`. El campo de esquema pasa a

```kotlin
            if (caps.refInCatalog) MField("Base de datos referida:") { FText(f.refNs.catalog.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(v.orNull(), null)) } }, "modify-fk-schema") }
            else MField("Esquema referido:") { FText(f.refNs.schema.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(null, v.orNull())) } }, "modify-fk-schema") }
```

- Índices: `ChoiceField(x.method, caps.indexMethods, …)` en lugar de `METHODS`; «Incluir columnas» y «Condición» dentro de `if (caps.indexExtras)`; `IndexColumnsEditor(x.columns, names, caps.indexExtras) …` con un parámetro nuevo `extras: Boolean` que oculta los campos de colación y clase de operador (`if (extras) { FText(… "modify-ix-coll:$i" …); FText(… "modify-ix-opc:$i" …) }`).
- Columna (`ColumnForm(draft, i)`): usar `val caps = draft.caps`. El texto de la casilla de identity es `if (caps.singleIdentity) "auto_increment" else "Identity"`; el bloque `if (ident != null) { … }` de «Siempre», «Mínimo», «Máximo», «Paso», … pasa a `if (ident != null && caps.identityOptions)`. Tras el campo «Generada como:», si `caps.virtualColumns`:

```kotlin
    if (caps.virtualColumns) MField("") { KCheckbox(col.generatedStored, { v -> edit { it.copy(generatedStored = v) } }, label = "Almacenada", enabled = col.origin == null && col.generated != null, modifier = Modifier.testTag("modify-col-stored")) }
```

- [ ] **Step 4: Aplicar sin transacción en `AppState`**

En `AppState.kt`, `runModify` pasa a:

```kotlin
    private fun runModify(statements: List<String>) {
        val d = modifyDraft ?: return
        val (target, pw) = modifyTarget ?: return
        if (modifyBusy) return
        modifyBusy = true; modifyError = null
        val atomic = d.caps.atomic
        scope.launch {
            val mysql = target.kind == DbKind.MYSQL || target.kind == DbKind.MARIADB
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (atomic) ApplyResult(0, runAtomically(target, pw, if (target.kind == DbKind.POSTGRES) listOf("set local lock_timeout = '5s'") + statements else statements))
                    else runStepwise(target, pw, statements, if (mysql) listOf("set session lock_wait_timeout = 5") else emptyList())
                }.getOrElse { ApplyResult(0, it.message ?: it.toString()) }
            }
            val error = result.error?.let {
                if (it.contains("lock timeout", true) || it.contains("Lock wait timeout", true)) "$it\nOtra transacción (quizá una abierta en esta aplicación: confírmala o deshazla) tiene bloqueada la tabla." else it
            }
            val ran = if (atomic) 0 else result.applied
            val now = System.currentTimeMillis()
            // all or nothing: every statement shares the outcome; one by one: the ones that ran are ok and the one that failed is not
            record(target.id, if (atomic) statements.map { HistoryEntry(now, "$it;", 0, ok = error == null) }
                else statements.take(ran).map { HistoryEntry(now, "$it;", 0, ok = true) } + listOfNotNull(error?.let { statements.getOrNull(ran)?.let { s -> HistoryEntry(now, "$s;", 0, ok = false) } }))
            modifyBusy = false
            val open = modifyDraft === d
            if (error != null) {
                val partial = if (!atomic && ran > 0) "Se aplicaron $ran de ${statements.size} sentencias; el resto no se ejecutó.\n" else ""
                if (open) modifyError = partial + error else status = "No se pudo modificar la tabla: $partial$error"
                if (!atomic && ran > 0) { refreshExplorer(); if (open) reloadModify(partial + error) }
                return@launch
            }
            if (open) closeModify()
            status = "Tabla modificada"
            refreshExplorer()
        }
    }

    /** After a partial apply the dialog starts again from what the server has now, keeping the error on screen. */
    fun reloadModify(error: String? = null) {
        val d = modifyDraft ?: return
        val m = explorer ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { m.source.tableModel(d.obj) } }
                .onSuccess { fresh -> if (fresh != null && modifyDraft === d) { modifyDraft = ModifyDraft(d.obj, fresh, m.source.dialect); modifyError = error } }
                .onFailure { status = "No se pudo releer ${d.obj.name}: ${it.message}" }
        }
    }
```

Añadir a los imports de `AppState.kt`: `ksqlc.drivers.ApplyResult` y `ksqlc.drivers.runStepwise` (junto a `runAtomically`).

- [ ] **Step 5: Habilitar el menú**

En `Alterations.kt`:

```kotlin
/** Engines whose tables the Modify dialog can change. */
fun supportsModifyTable(kind: DbKind) = kind in setOf(DbKind.POSTGRES, DbKind.MYSQL, DbKind.MARIADB)
```

- [ ] **Step 6: Prueba de interfaz con MySQL (Testcontainers)**

`app/src/test/kotlin/ksqlc/app/ModifyMySqlUiTest.kt`, con el mismo armazón que `ModifyUiTest` (copiar `state`, `until`, `exists`, `frames`, `tearDown`) pero con `MySQLContainer("mysql:8.4")`, conexión como `root` y esta tabla:

```kotlin
c.createStatement().execute("create database ui")
c.createStatement().execute("create table ui.mt(id int not null auto_increment primary key, name varchar(40))")
```

Tests:

```kotlin
    @Test fun theDialogShowsTheNoticeHidesPostgresFieldsAndApplies() {
        open()                                                    // openModify(DbObject(Namespace("ui", null), "mt", TABLE))
        rule.onNodeWithTag("modify:keys:0").performClick(); frames()
        assertFalse(exists("modify-key-deferrable"))               // MySQL has no deferrable keys
        rule.onNodeWithTag("modify:columns:1").performClick(); frames()
        rule.onNodeWithTag("modify-col-name").performTextReplacement("nombre"); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("cada sentencia", substring = true)
        rule.onNodeWithTag("modify-preview").assertTextContains("rename column `name` to `nombre`", substring = true)
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyDraft == null }
        assertEquals(listOf("id", "nombre"), columns())            // information_schema.columns of ui.mt
    }

    @Test fun aFailureInTheMiddleReloadsTheDialogWithTheErrorAndWhatWasApplied() {
        open()
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()           // adds a column: statement 1
        rule.onNodeWithTag("modify:checks:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()           // adds a check
        rule.onNodeWithTag("modify:checks:0").performClick(); frames()
        rule.onNodeWithTag("modify-ck-name").performTextReplacement("mt_never_ck"); frames()
        rule.onNodeWithTag("modify-ck-expr").performTextReplacement("1 = 0"); frames()     // no row satisfies it: statement 2 fails
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyError != null }
        assertTrue(state.modifyError!!.startsWith("Se aplicaron 1 de 2"), state.modifyError)
        until { state.modifyDraft!!.before.columns.size == 3 }              // reloaded from the server: the new column is there
        assertTrue(exists("modify-error"))
    }
```

Para que el check falle la tabla necesita una fila: en el `setup` del test añadir `insert into ui.mt(name) values ('a')`. Antes de los tests, la clase importa `kotlin.test.assertFalse` y `kotlin.test.assertTrue`.

Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyMySqlUiTest' --tests 'ksqlc.app.ModifyUiTest' --tests 'ksqlc.app.ModifyDraftTest'`
Expected: PASS (el de PostgreSQL sigue pasando).

- [ ] **Step 7: Commit**

```bash
git add app drivers
git commit -m "Modificar tabla: diálogo y aplicación para MySQL y MariaDB, con el aviso de que cada sentencia confirma

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Documentación y suite completa

**Files:**
- Modify: `docs/spec/feature-inventory.md` (fila «Modificar tabla»)
- Modify: `docs/spec/ui-design.md` (tabla de diálogos)
- Modify: `docs/superpowers/specs/2026-10-16-modificar-tabla-mysql-design.md` (estado)

- [ ] **Step 1: Inventario**

En la fila «Modificar tabla» de `feature-inventory.md`: cambiar «(solo en conexiones PostgreSQL que no son de solo lectura)» por «(solo en conexiones PostgreSQL, MySQL y MariaDB que no son de solo lectura)», quitar de «No se hizo» la frase «ni los demás motores (el diálogo y el modelo no dependen del motor; falta leer y generar el SQL de cada uno)» y poner en su lugar «ni SQL Server, Oracle ni SQLite». Añadir al final de la celda:

> MySQL y MariaDB (8.0.19+ y 10.5.2+): el DDL confirma cada sentencia, así que se aplica una a una y, si una falla, se para: quedan aplicadas las anteriores, se avisa de cuántas y el diálogo se recarga desde el servidor. `auto_increment` es el identity (una por tabla, la primera de una clave). Sin diferibles, clase de operador, colación por columna de índice, `include` ni índices parciales; los índices fulltext, spatial y con prefijo no se leen (no se tocan). Un índice único se lee como clave única; la clave primaria se llama siempre `PRIMARY` y renombrarla no hace nada. Cambiar una columna con `modify column` reescribe su definición y pierde el charset y la collation propios de la columna y `on update`. Una columna generada solo se define al crearla (almacenada o virtual).

- [ ] **Step 2: Diseño de interfaz**

En la fila «Modificar tabla» de `ui-design.md` añadir al final: «En MySQL y MariaDB la vista previa empieza con un aviso («MySQL y MariaDB confirman cada sentencia…»), el formulario oculta lo que el motor no tiene y la clave foránea pide la base de datos referida en vez del esquema.»

- [ ] **Step 3: Estado del spec**

En el spec de MySQL: `Estado: pendiente de revisión` → `Estado: implementado`.

- [ ] **Step 4: Suite completa**

Run: `./gradlew test`
Expected: PASS (con Docker, incluidos los de servidor; sin Docker se omiten).

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "Documentación: modificar tabla en MySQL y MariaDB

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
