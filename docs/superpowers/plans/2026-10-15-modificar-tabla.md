# Modificar una tabla (diálogo «Modify») — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Modificar…» sobre una tabla de PostgreSQL abre un diálogo con árbol, formularios y vista previa del SQL; OK lo aplica todo en una transacción.

**Architecture:** Un `TableModel` inmutable (en `core`) que la base de datos lee (`Dialect.tableModel`) y el diálogo edita. Una función pura (`Dialect.alterTable(o, before, after)`) compara el modelo original con el editado y devuelve las sentencias `ALTER`. El diálogo es un `ModifyDraft` observable más una composable; `runAtomically` aplica las sentencias en una sola transacción.

**Tech Stack:** Kotlin, Compose Desktop (material3), JDBC (pgjdbc), JUnit + `kotlin.test`, Testcontainers (PostgreSQL 16).

**Spec:** `docs/superpowers/specs/2026-10-15-modificar-tabla-design.md`

## Global Constraints

- Solo PostgreSQL y solo tablas (`relkind` `r` y `p`); `supportsModifyTable(kind)` es `kind == DbKind.POSTGRES`.
- No se toca `Dialect.details` ni `Dialect.ddl`: el explorador y «Ver DDL» no cambian.
- Todas las sentencias se ejecutan en **una** transacción; si una falla se deshace todo y el diálogo sigue abierto con el error.
- Los índices que respaldan una clave (`pg_constraint.conindid` con `contype` `p`/`u`/`x` de la propia tabla) no entran en `indexes`.
- Nombres entrecomillados con `Dialect.quote`; literales de comentario duplican las comillas simples.
- La entrada «Modificar…» no aparece con la conexión en solo lectura.
- Texto de la interfaz en español; etiquetas de los campos del formulario en español.
- Etiquetas de prueba (`testTag`): `modify-dialog`, `modify-preview`, `modify-error`, `modify-ok`, `modify-cancel`, `modify-add`, `modify-remove`, `modify-duplicate`, nodos `modify:<grupo>:<índice>` (grupos `table`, `columns`, `keys`, `fks`, `indexes`, `checks`; índice `-1` en la fila de tabla o de grupo).
- Comentarios de código: una línea y solo el porqué. Mensajes de commit en español, con la línea `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` al final.
- Los tests contra servidor necesitan Docker (`assumeDocker()` los omite si no hay). Se lanzan con `./gradlew :drivers:test --tests '<clase>' -q` o `:app:test`.

## Review Focus

- Renombrar una columna que usan una clave, un índice o una FK debe dar **solo** el `rename column`, sin quitar ni recrear nada (Task 2 y Task 3).
- Cambiar el tipo de una columna con `default` debe quitar el default antes del `type … using` y volver a ponerlo después (Task 2).
- Nombres y comentarios con comillas, mayúsculas o palabras reservadas (`a"b`, `it's`, tabla `Order`) deben salir bien entrecomillados (Task 2).
- Una sentencia que falla a mitad no deja nada aplicado y el diálogo muestra el error (Task 2, `runAtomically`).
- Sin cambios o con un modelo inválido no hay sentencias y OK queda deshabilitado (Task 3 y Task 4).

---

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `core/src/main/kotlin/ksqlc/core/TableModel.kt` (nuevo) | Los datos del modelo editable. |
| `drivers/.../dialect/Dialect.kt` | Dos métodos nuevos con valor por omisión: `tableModel`, `alterTable`. |
| `drivers/.../dialect/PostgresDialect.kt` | Lee el modelo (`tableModel`) y delega `alterTable`. |
| `drivers/.../dialect/PostgresAlter.kt` (nuevo) | Generador de sentencias, función pura. |
| `drivers/.../TableModelRules.kt` (nuevo) | `tableProblem` (validación) y `destructiveChanges`. |
| `drivers/.../TableApply.kt` (nuevo) | `runAtomically`. |
| `drivers/.../SchemaSource.kt`, `Introspector.kt` | `tableModel(o)` hacia el explorador. |
| `drivers/.../Alterations.kt` | `supportsModifyTable`. |
| `app/.../ModifyDraft.kt` (nuevo) | Estado observable del diálogo y operaciones de edición. |
| `app/.../ModifyDialog.kt` (nuevo) | Árbol, formularios y vista previa. |
| `app/.../AppState.kt`, `ExplorerPanel.kt`, `MainWindow.kt` | Abrir, aplicar, menú y montaje. |

---

### Task 1: Modelo y lectura de PostgreSQL

**Files:**
- Create: `core/src/main/kotlin/ksqlc/core/TableModel.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/Dialect.kt`, `drivers/src/main/kotlin/ksqlc/drivers/dialect/PostgresDialect.kt`, `drivers/src/main/kotlin/ksqlc/drivers/SchemaSource.kt`, `drivers/src/main/kotlin/ksqlc/drivers/Introspector.kt`, `drivers/src/main/kotlin/ksqlc/drivers/Alterations.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/server/TableModelServerTest.kt`

**Interfaces:**
- Produces: `TableModel`, `ColumnModel`, `Identity`, `KeyModel`, `ForeignKeyModel`, `IndexModel`, `IndexColumn`, `CheckModel` (campos abajo); `Dialect.tableModel(conn, o): TableModel?` (null por omisión); `SchemaSource.tableModel(o): TableModel?`; `supportsModifyTable(kind: DbKind): Boolean`.

- [ ] **Step 1: Escribir el modelo**

`core/src/main/kotlin/ksqlc/core/TableModel.kt`:

```kotlin
package ksqlc.core

/** An editable copy of a table, what the Modify dialog edits. [origin] of each element is its name in the database, null when it is new. */
data class TableModel(
    val name: String,
    val comment: String? = null,
    val columns: List<ColumnModel> = emptyList(),
    val keys: List<KeyModel> = emptyList(),
    val foreignKeys: List<ForeignKeyModel> = emptyList(),
    val indexes: List<IndexModel> = emptyList(),
    val checks: List<CheckModel> = emptyList(),
)

/** Sequence options of an identity column; a null option is the server's default. */
data class Identity(
    val always: Boolean = false,
    val min: String? = null, val max: String? = null, val step: String? = null, val cache: String? = null,
    val cycle: Boolean = false,
)

/** [type] and [default] are the text PostgreSQL gives (`format_type`, `pg_get_expr`); [generated] is the expression of a stored generated column. */
data class ColumnModel(
    val origin: String?, val name: String, val type: String,
    val notNull: Boolean = false, val default: String? = null, val comment: String? = null,
    val identity: Identity? = null, val generated: String? = null,
)

data class KeyModel(
    val origin: String?, val name: String, val primary: Boolean, val columns: List<String>,
    val deferrable: Boolean = false, val initiallyDeferred: Boolean = false,
)

/** [onDelete] and [onUpdate] are `NO ACTION`, `RESTRICT`, `CASCADE`, `SET NULL` or `SET DEFAULT`. */
data class ForeignKeyModel(
    val origin: String?, val name: String, val columns: List<String>,
    val refNs: Namespace, val refTable: String, val refColumns: List<String>,
    val onDelete: String = "NO ACTION", val onUpdate: String = "NO ACTION",
    val deferrable: Boolean = false, val initiallyDeferred: Boolean = false,
)

/** [name] is a column name, or an SQL expression when [expression]; [order] is "", "ASC" or "DESC". */
data class IndexColumn(
    val name: String, val expression: Boolean = false,
    val order: String = "", val collation: String? = null, val opClass: String? = null,
)

data class IndexModel(
    val origin: String?, val name: String, val unique: Boolean, val method: String = "btree",
    val columns: List<IndexColumn>, val include: List<String> = emptyList(), val condition: String? = null,
)

data class CheckModel(val origin: String?, val name: String, val expression: String)
```

- [ ] **Step 2: Escribir el test que falla**

`drivers/src/test/kotlin/ksqlc/drivers/server/TableModelServerTest.kt`:

```kotlin
package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

class TableModelServerTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema mt",
                "create table mt.parent(id int primary key)",
                """create table mt.t(
                     id bigint generated by default as identity (start 1 increment by 5 minvalue 1 maxvalue 1000 cache 2 cycle),
                     name varchar(120) not null default 'x',
                     email text,
                     parent_id int,
                     price numeric(10,2),
                     total numeric generated always as (price * 2) stored,
                     constraint t_pkey primary key (id),
                     constraint t_email_key unique (email) deferrable initially deferred,
                     constraint t_parent_fk foreign key (parent_id) references mt.parent(id) on delete cascade,
                     constraint t_price_ck check (price > 0))""",
                "create index t_name_idx on mt.t (lower(name), email desc) include (price) where price > 1",
                "comment on table mt.t is 'hola'",
                "comment on column mt.t.name is 'nombre'",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val t = DbObject(Namespace(null, "mt"), "t", DbObjectKind.TABLE)
    private fun model() = Introspector(fx.ds, fx.password).use { it.tableModel(t)!! }

    @Test fun readsColumnsWithIdentityDefaultsGeneratedAndComments() = model().let { m ->
        assertEquals("t", m.name); assertEquals("hola", m.comment)
        assertEquals(listOf("id", "name", "email", "parent_id", "price", "total"), m.columns.map { it.name })
        assertEquals(m.columns.map { it.name }, m.columns.map { it.origin })
        val id = m.columns[0]
        assertEquals("bigint", id.type); assertTrue(id.notNull); assertNull(id.default)
        assertEquals(Identity(always = false, min = "1", max = "1000", step = "5", cache = "2", cycle = true), id.identity)
        val name = m.columns[1]
        assertEquals("character varying(120)", name.type); assertTrue(name.notNull); assertEquals("nombre", name.comment)
        assertTrue(name.default!!.startsWith("'x'"), name.default)
        assertTrue(m.columns[5].generated!!.contains("price"), m.columns[5].generated)
        assertNull(m.columns[5].default)
    }

    @Test fun readsKeysForeignKeysAndChecks() = model().let { m ->
        assertEquals(
            listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id")), KeyModel("t_email_key", "t_email_key", false, listOf("email"), deferrable = true, initiallyDeferred = true)),
            m.keys,
        )
        assertEquals(
            listOf(ForeignKeyModel("t_parent_fk", "t_parent_fk", listOf("parent_id"), Namespace(null, "mt"), "parent", listOf("id"), onDelete = "CASCADE")),
            m.foreignKeys,
        )
        assertEquals(listOf("t_price_ck"), m.checks.map { it.name }); assertTrue(m.checks.single().expression.contains("price > 0"))
    }

    @Test fun readsOnlyTheIndexesThatDoNotBackAKey() = model().let { m ->
        val ix = m.indexes.single()      // t_pkey and t_email_key are keys, not indexes
        assertEquals("t_name_idx", ix.name); assertFalse(ix.unique); assertEquals("btree", ix.method)
        assertTrue(ix.columns[0].expression && ix.columns[0].name.startsWith("lower("), ix.columns[0].name)
        assertEquals(IndexColumn("email", order = "DESC"), ix.columns[1])
        assertEquals(listOf("price"), ix.include); assertTrue(ix.condition!!.contains("price > 1"), ix.condition)
    }

    @Test fun aMissingTableIsAnError() {
        Introspector(fx.ds, fx.password).use { assertFailsWith<IllegalStateException> { it.tableModel(DbObject(Namespace(null, "mt"), "nope", DbObjectKind.TABLE)) } }
    }
}
```

- [ ] **Step 3: Ejecutarlo y ver que falla**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableModelServerTest' -q`
Expected: no compila (`tableModel` no existe).

- [ ] **Step 4: Añadir los métodos a `Dialect`, `SchemaSource` e `Introspector`, y `supportsModifyTable`**

En `Dialect.kt`, dentro de la interfaz, tras `call`:

```kotlin
    /** An editable copy of table [o], or null when the engine's tables cannot be modified yet. */
    fun tableModel(conn: Connection, o: DbObject): TableModel? = null

    /** The statements that turn [before] into [after] on table [o]; empty when nothing changed. */
    fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = error("Este motor no puede modificar tablas")
```

En `SchemaSource.kt`, tras `call`:

```kotlin
    fun tableModel(o: DbObject): TableModel? = null
```

En `Introspector.kt`, tras `call`:

```kotlin
    @Synchronized override fun tableModel(o: DbObject): TableModel? = dialect.tableModel(connection(), o)
```

En `Alterations.kt`, al final:

```kotlin

/** Engines whose tables the Modify dialog can change. */
fun supportsModifyTable(kind: DbKind) = kind == DbKind.POSTGRES
```

- [ ] **Step 5: Implementar la lectura en `PostgresDialect`**

Añadir en `PostgresDialect.kt`, dentro del objeto (antes de `override fun ddl`), y `import java.sql.ResultSet` si falta:

```kotlin
    override fun tableModel(conn: Connection, o: DbObject): TableModel {
        val (oid, tableComment) = conn.query(
            """select c.oid::bigint, obj_description(c.oid, 'pg_class') from pg_class c join pg_namespace n on n.oid = c.relnamespace
               where n.nspname = ? and c.relname = ? and c.relkind in ('r', 'p')""",
            o.ns.schema, o.name,
        ) { it.getLong(1) to it.getString(2) }.firstOrNull() ?: error("${o.name} no existe")

        val columns = conn.query(
            """select a.attname::text, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid), a.attgenerated::text,
                      col_description(a.attrelid, a.attnum), a.attidentity::text, s.seqmin, s.seqmax, s.seqincrement, s.seqcache, s.seqcycle
               from pg_attribute a
               left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
               left join pg_depend dep on a.attidentity <> '' and dep.refclassid = 'pg_class'::regclass and dep.refobjid = a.attrelid
                    and dep.refobjsubid = a.attnum and dep.classid = 'pg_class'::regclass and dep.deptype = 'i'
               left join pg_sequence s on s.seqrelid = dep.objid
               where a.attrelid = ?::oid and a.attnum > 0 and not a.attisdropped order by a.attnum""",
            oid,
        ) { rs ->
            val expr = rs.getString(4)
            val generated = rs.getString(5).isNotEmpty()
            val identity = rs.getString(7).takeIf { it.isNotEmpty() }?.let { mode ->
                Identity(always = mode == "a", min = rs.getString(8), max = rs.getString(9), step = rs.getString(10), cache = rs.getString(11), cycle = rs.getBoolean(12))
            }
            ColumnModel(rs.getString(1), rs.getString(1), rs.getString(2), rs.getBoolean(3), expr.takeIf { !generated }, rs.getString(6), identity, expr.takeIf { generated })
        }

        val keys = conn.query(
            """select con.conname::text, con.contype::text, con.condeferrable, con.condeferred,
                      array(select a.attname::text from unnest(con.conkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.num order by k.ord)
               from pg_constraint con where con.conrelid = ?::oid and con.contype in ('p', 'u') order by con.contype <> 'p', con.conname""",
            oid,
        ) { rs -> KeyModel(rs.getString(1), rs.getString(1), rs.getString(2) == "p", rs.names(5), rs.getBoolean(3), rs.getBoolean(4)) }

        val actions = mapOf("a" to "NO ACTION", "r" to "RESTRICT", "c" to "CASCADE", "n" to "SET NULL", "d" to "SET DEFAULT")
        val foreignKeys = conn.query(
            """select con.conname::text, con.confdeltype::text, con.confupdtype::text, con.condeferrable, con.condeferred, n.nspname::text, c.relname::text,
                      array(select a.attname::text from unnest(con.conkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.num order by k.ord),
                      array(select a.attname::text from unnest(con.confkey) with ordinality k(num, ord)
                            join pg_attribute a on a.attrelid = con.confrelid and a.attnum = k.num order by k.ord)
               from pg_constraint con join pg_class c on c.oid = con.confrelid join pg_namespace n on n.oid = c.relnamespace
               where con.conrelid = ?::oid and con.contype = 'f' order by con.conname""",
            oid,
        ) { rs ->
            ForeignKeyModel(rs.getString(1), rs.getString(1), rs.names(8), Namespace(null, rs.getString(6)), rs.getString(7), rs.names(9),
                actions.getValue(rs.getString(2)), actions.getValue(rs.getString(3)), rs.getBoolean(4), rs.getBoolean(5))
        }

        val checks = conn.query(
            "select conname::text, pg_get_expr(conbin, conrelid) from pg_constraint where conrelid = ?::oid and contype = 'c' order by conname", oid,
        ) { CheckModel(it.getString(1), it.getString(1), it.getString(2)) }

        class IndexRow(val name: String, val unique: Boolean, val method: String, val keyCount: Int, val condition: String?, val n: Int,
            val def: String, val expression: Boolean, val desc: Boolean, val collation: String?, val opClass: String?)
        val rows = conn.query(
            """select ic.relname::text, i.indisunique, am.amname::text, i.indnkeyatts::int, pg_get_expr(i.indpred, i.indrelid), k.n,
                      pg_get_indexdef(i.indexrelid, k.n, true), i.indkey[k.n - 1] = 0, (i.indoption[k.n - 1] & 1) <> 0,
                      case when i.indcollation[k.n - 1] not in (0, 100) then (select collname::text from pg_collation where oid = i.indcollation[k.n - 1]) end,
                      case when not opc.opcdefault then opc.opcname::text end
               from pg_index i
               join pg_class ic on ic.oid = i.indexrelid
               join pg_am am on am.oid = ic.relam
               cross join generate_series(1, i.indnatts::int) k(n)
               left join pg_opclass opc on opc.oid = i.indclass[k.n - 1]
               where i.indrelid = ?::oid
                 and not exists (select 1 from pg_constraint con where con.conindid = i.indexrelid and con.conrelid = i.indrelid and con.contype in ('p', 'u', 'x'))
               order by ic.relname, k.n""",
            oid,
        ) { rs -> IndexRow(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9), rs.getString(10), rs.getString(11)) }
        fun unquote(s: String) = if (s.startsWith("\"")) s.substring(1, s.length - 1).replace("\"\"", "\"") else s
        val indexes = rows.groupBy { it.name }.map { (name, parts) ->
            val first = parts.first()
            IndexModel(name, name, first.unique, first.method,
                parts.filter { it.n <= first.keyCount }.map { IndexColumn(if (it.expression) it.def else unquote(it.def), it.expression, if (it.desc) "DESC" else "", it.collation, it.opClass) },
                parts.filter { it.n > first.keyCount }.map { unquote(it.def) }, first.condition)
        }

        return TableModel(o.name, tableComment, columns, keys, foreignKeys, indexes, checks)
    }

    private fun ResultSet.names(i: Int): List<String> = (getArray(i).array as Array<*>).map { it.toString() }
```

Si `Identity`, `KeyModel` etc. no resuelven, ya entran por `import ksqlc.core.*` de `PostgresDialect.kt`.

- [ ] **Step 6: Ejecutar el test**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableModelServerTest' -q`
Expected: 4 tests pasan. Si una consulta falla, corregir el SQL contra el mensaje del servidor (los operadores de `int2vector`/`oidvector` son de índice 0; `generate_series` necesita `int`).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/ksqlc/core/TableModel.kt drivers/src/main drivers/src/test/kotlin/ksqlc/drivers/server/TableModelServerTest.kt
git commit -m "Modelo editable de tabla y su lectura en PostgreSQL

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Generador de sentencias, reglas y aplicación atómica

**Files:**
- Create: `drivers/src/main/kotlin/ksqlc/drivers/dialect/PostgresAlter.kt`, `drivers/src/main/kotlin/ksqlc/drivers/TableModelRules.kt`, `drivers/src/main/kotlin/ksqlc/drivers/TableApply.kt`
- Modify: `drivers/src/main/kotlin/ksqlc/drivers/dialect/PostgresDialect.kt`
- Test: `drivers/src/test/kotlin/ksqlc/drivers/TableAlterTest.kt`, `drivers/src/test/kotlin/ksqlc/drivers/TableModelRulesTest.kt`, `drivers/src/test/kotlin/ksqlc/drivers/server/TableAlterServerTest.kt`

**Interfaces:**
- Consumes: modelo y `Dialect.alterTable` de la Task 1.
- Produces: `PostgresDialect.alterTable(o, before, after)`; `fun tableProblem(m: TableModel): String?`; `fun destructiveChanges(before: TableModel, after: TableModel): List<String>`; `fun runAtomically(ds: DataSource, password: String?, statements: List<String>): String?` (mensaje de error o null).

- [ ] **Step 1: Escribir los tests del generador (fallan)**

`drivers/src/test/kotlin/ksqlc/drivers/TableAlterTest.kt`:

```kotlin
package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.PostgresDialect
import kotlin.test.*

class TableAlterTest {
    private val ns = Namespace(null, "app")
    private val tbl = DbObject(ns, "t", DbObjectKind.TABLE)
    private val q = "\"app\".\"t\""
    private fun col(name: String, type: String = "integer", notNull: Boolean = false, default: String? = null) = ColumnModel(name, name, type, notNull, default)
    private val base = TableModel(
        "t", null,
        columns = listOf(col("id", "bigint", true), col("name", "text")),
        keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id"))),
        indexes = listOf(IndexModel("t_name_idx", "t_name_idx", false, "btree", listOf(IndexColumn("name")))),
        checks = listOf(CheckModel("t_id_ck", "t_id_ck", "(id > 0)")),
    )
    private fun alter(before: TableModel, after: TableModel) = PostgresDialect.alterTable(tbl, before, after)
    private fun diff(after: TableModel) = alter(base, after)

    @Test fun noChangesNoStatements() = assertEquals(emptyList(), diff(base))

    @Test fun renamingAColumnKeepsItsKeysAndIndexes() {
        val after = base.copy(
            columns = listOf(base.columns[0], base.columns[1].copy(name = "title")),
            indexes = listOf(base.indexes[0].copy(columns = listOf(IndexColumn("title")))),
        )
        assertEquals(listOf("alter table $q rename column \"name\" to \"title\""), diff(after))
    }

    @Test fun dropAndAddColumns() {
        val after = base.copy(
            columns = listOf(base.columns[0], ColumnModel(null, "age", "integer", notNull = true, default = "0", comment = "edad")),
            indexes = emptyList(),
        )
        assertEquals(listOf(
            "drop index \"app\".\"t_name_idx\"",
            "alter table $q drop column \"name\"",
            "alter table $q add column \"age\" integer default 0 not null",
            "comment on column $q.\"age\" is 'edad'",
        ), diff(after))
    }

    @Test fun changingTheTypeOfAColumnWithADefaultDropsAndRestoresIt() {
        val before = base.copy(columns = listOf(base.columns[0], col("name", "text", default = "'x'::text")))
        val after = base.copy(columns = listOf(base.columns[0], before.columns[1].copy(type = "varchar(50)")))
        assertEquals(listOf(
            "alter table $q alter column \"name\" drop default",
            "alter table $q alter column \"name\" type varchar(50) using \"name\"::varchar(50)",
            "alter table $q alter column \"name\" set default 'x'::text",
        ), alter(before, after))
    }

    @Test fun notNullDefaultAndCommentOfAKeptColumn() {
        val set = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(notNull = true, default = "'n'", comment = "c")))
        assertEquals(listOf(
            "alter table $q alter column \"name\" set not null",
            "alter table $q alter column \"name\" set default 'n'",
            "comment on column $q.\"name\" is 'c'",
        ), diff(set))
        assertEquals(listOf(
            "alter table $q alter column \"name\" drop not null",
            "alter table $q alter column \"name\" drop default",
            "comment on column $q.\"name\" is null",
        ), alter(set, base))
    }

    @Test fun identityIsAddedDroppedAndTuned() {
        val withIdentity = { i: Identity -> base.copy(columns = listOf(base.columns[0].copy(identity = i), base.columns[1])) }
        assertEquals(
            listOf("alter table $q alter column \"id\" add generated by default as identity (increment by 5 minvalue 1 cycle)"),
            diff(withIdentity(Identity(step = "5", min = "1", cycle = true))),
        )
        assertEquals(listOf("alter table $q alter column \"id\" drop identity"), alter(withIdentity(Identity()), base))
        assertEquals(listOf(
            "alter table $q alter column \"id\" set generated always",
            "alter table $q alter column \"id\" set increment by 2",
            "alter table $q alter column \"id\" set maxvalue 9",
        ), alter(withIdentity(Identity(step = "1", cache = "1")), withIdentity(Identity(always = true, step = "2", max = "9", cache = "1"))))
    }

    @Test fun aKeyWithNewColumnsIsRecreatedAndAKeyWithOnlyANewNameIsRenamed() {
        val wider = base.copy(keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id", "name"))))
        assertEquals(listOf(
            "alter table $q drop constraint \"t_pkey\"",
            "alter table $q add constraint \"t_pkey\" primary key (\"id\", \"name\")",
        ), diff(wider))
        val renamed = base.copy(keys = listOf(KeyModel("t_pkey", "t_pk", true, listOf("id"))))
        assertEquals(listOf("alter table $q rename constraint \"t_pkey\" to \"t_pk\""), diff(renamed))
    }

    @Test fun dropsGoForeignKeysChecksKeysThenIndexes() {
        val before = base.copy(foreignKeys = listOf(ForeignKeyModel("t_fk", "t_fk", listOf("id"), ns, "parent", listOf("id"))))
        val after = before.copy(foreignKeys = emptyList(), keys = emptyList(), indexes = emptyList(), checks = emptyList())
        assertEquals(listOf(
            "alter table $q drop constraint \"t_fk\"",
            "alter table $q drop constraint \"t_id_ck\"",
            "alter table $q drop constraint \"t_pkey\"",
            "drop index \"app\".\"t_name_idx\"",
        ), alter(before, after))
    }

    @Test fun newConstraintsAndIndexesAreCreatedKeysChecksIndexesThenForeignKeys() {
        val after = base.copy(
            keys = base.keys + KeyModel(null, "t_name_key", false, listOf("name"), deferrable = true, initiallyDeferred = true),
            checks = base.checks + CheckModel(null, "t_name_ck", "(length(name) > 0)"),
            foreignKeys = listOf(ForeignKeyModel(null, "t_fk", listOf("id"), ns, "parent", listOf("id"), onDelete = "CASCADE")),
            indexes = base.indexes + IndexModel(
                null, "t_ix", true, "gin",
                listOf(IndexColumn("lower(name)", expression = true, order = "DESC"), IndexColumn("id", collation = "C", opClass = "int8_ops")),
                include = listOf("name"), condition = "(id > 0)",
            ),
        )
        assertEquals(listOf(
            "alter table $q add constraint \"t_name_key\" unique (\"name\") deferrable initially deferred",
            "alter table $q add constraint \"t_name_ck\" check ((length(name) > 0))",
            "create unique index \"t_ix\" on $q using gin ((lower(name)) DESC, \"id\" collate \"C\" int8_ops) include (\"name\") where (id > 0)",
            "alter table $q add constraint \"t_fk\" foreign key (\"id\") references \"app\".\"parent\" (\"id\") on delete CASCADE on update NO ACTION",
        ), diff(after))
    }

    @Test fun anIndexThatOnlyChangesNameIsRenamed() {
        val after = base.copy(indexes = listOf(base.indexes[0].copy(name = "t_title_idx")))
        assertEquals(listOf("alter index \"app\".\"t_name_idx\" rename to \"t_title_idx\""), diff(after))
    }

    @Test fun tableCommentThenRenameAndQuotesAreEscaped() {
        assertEquals(listOf("comment on table $q is 'it''s'", "alter table $q rename to \"t2\""), diff(base.copy(name = "t2", comment = "it's")))
        val odd = base.copy(columns = base.columns + ColumnModel(null, "a\"b", "integer"))
        assertEquals(listOf("alter table $q add column \"a\"\"b\" integer"), diff(odd))
        val order = DbObject(ns, "Order", DbObjectKind.TABLE)
        assertEquals(listOf("alter table \"app\".\"Order\" rename to \"Order2\""), PostgresDialect.alterTable(order, base.copy(name = "Order"), base.copy(name = "Order2")))
    }

    @Test fun generatedColumnsAndIdentityOnANewColumn() {
        val after = base.copy(columns = base.columns + listOf(
            ColumnModel(null, "double_id", "bigint", generated = "id * 2"),
            ColumnModel(null, "seq", "integer", notNull = true, identity = Identity(always = true)),
        ))
        assertEquals(listOf(
            "alter table $q add column \"double_id\" bigint generated always as (id * 2) stored",
            "alter table $q add column \"seq\" integer generated always as identity not null",
        ), diff(after))
    }
}
```

- [ ] **Step 2: Escribir los tests de las reglas (fallan)**

`drivers/src/test/kotlin/ksqlc/drivers/TableModelRulesTest.kt`:

```kotlin
package ksqlc.drivers

import ksqlc.core.*
import kotlin.test.*

class TableModelRulesTest {
    private val ns = Namespace(null, "app")
    private val ok = TableModel(
        "t", null,
        columns = listOf(ColumnModel("id", "id", "bigint", true), ColumnModel("name", "name", "text")),
        keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id"))),
        indexes = listOf(IndexModel("ix", "ix", false, "btree", listOf(IndexColumn("name")))),
    )

    @Test fun aValidModelHasNoProblem() = assertNull(tableProblem(ok))

    @Test fun reportsTheFirstProblem() {
        assertNotNull(tableProblem(ok.copy(name = " ")))
        assertNotNull(tableProblem(ok.copy(columns = emptyList())))
        assertNotNull(tableProblem(ok.copy(columns = ok.columns + ColumnModel(null, "ID", "int"))))      // repeated, ignoring case
        assertNotNull(tableProblem(ok.copy(columns = listOf(ok.columns[0].copy(type = " "), ok.columns[1]))))
        assertNotNull(tableProblem(ok.copy(columns = listOf(ok.columns[0].copy(identity = Identity(), notNull = false), ok.columns[1]))))
        assertNotNull(tableProblem(ok.copy(keys = listOf(ok.keys[0].copy(columns = emptyList())))))
        assertNotNull(tableProblem(ok.copy(keys = listOf(ok.keys[0].copy(columns = listOf("ghost"))))))
        assertNotNull(tableProblem(ok.copy(keys = ok.keys + KeyModel(null, "t_pk2", true, listOf("name")))))      // two primary keys
        assertNotNull(tableProblem(ok.copy(checks = listOf(CheckModel(null, "ck", " ")))))
        assertNotNull(tableProblem(ok.copy(checks = listOf(CheckModel(null, "t_pkey", "(id > 0)")))))      // name already used by a key
        assertNotNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = emptyList())))))
        assertNotNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = listOf(IndexColumn("ghost")))))))
        assertNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = listOf(IndexColumn("lower(name)", expression = true)))))))
        assertNotNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "", listOf("id"))))))
        assertNotNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "p", listOf("a", "b"))))))
        assertNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "p", listOf("id"))))))
    }

    @Test fun droppedColumnsAndTypeChangesLoseData() {
        assertEquals(emptyList(), destructiveChanges(ok, ok))
        val dropped = ok.copy(columns = listOf(ok.columns[0]))
        assertEquals(1, destructiveChanges(ok, dropped).size)
        assertTrue(destructiveChanges(ok, dropped).single().contains("name"))
        val retyped = ok.copy(columns = listOf(ok.columns[0], ok.columns[1].copy(type = "integer")))
        assertTrue(destructiveChanges(ok, retyped).single().contains("name"))
        val renamed = ok.copy(columns = listOf(ok.columns[0], ok.columns[1].copy(name = "title")))
        assertEquals(emptyList(), destructiveChanges(ok, renamed))
    }
}
```

- [ ] **Step 3: Ejecutar y ver que fallan**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.TableAlterTest' --tests 'ksqlc.drivers.TableModelRulesTest' -q`
Expected: no compila (`alterTable` de `PostgresDialect`, `tableProblem` y `destructiveChanges` no existen).

- [ ] **Step 4: Escribir el generador**

`drivers/src/main/kotlin/ksqlc/drivers/dialect/PostgresAlter.kt`:

```kotlin
package ksqlc.drivers.dialect

import ksqlc.core.*

private class Changes<T>(val dropped: List<T>, val created: List<T>, val renamed: List<Pair<T, T>>)

/** Pairs each [after] element with its [before] one by origin: a changed definition is dropped and created again, a new name alone is a rename. */
private fun <T> changes(before: List<T>, after: List<T>, origin: (T) -> String?, name: (T) -> String, same: (T, T) -> Boolean): Changes<T> {
    val pairs = after.mapNotNull { x -> before.find { name(it) == origin(x) }?.let { it to x } }
    val changed = pairs.filter { (y, x) -> !same(y, x) }
    return Changes(
        dropped = before.filter { y -> after.none { origin(it) == name(y) } } + changed.map { it.first },
        created = after.filter { origin(it) == null } + changed.map { it.second },
        renamed = pairs.filter { (y, x) -> same(y, x) && name(y) != name(x) },
    )
}

private fun identityOptions(i: Identity): String = listOfNotNull(
    i.step?.let { "increment by $it" }, i.min?.let { "minvalue $it" }, i.max?.let { "maxvalue $it" }, i.cache?.let { "cache $it" },
    if (i.cycle) "cycle" else null,
).takeIf { it.isNotEmpty() }?.joinToString(" ", " (", ")").orEmpty()

private fun deferral(deferrable: Boolean, initially: Boolean) = if (!deferrable) "" else if (initially) " deferrable initially deferred" else " deferrable"

internal fun postgresAlterTable(d: Dialect, o: DbObject, before: TableModel, after: TableModel): List<String> {
    val schema = o.ns.schema ?: error("La tabla no tiene esquema")
    val q = d.qualified(o)
    fun id(n: String) = d.quote(n)
    fun ids(l: List<String>) = l.joinToString(", ") { d.quote(it) }
    fun lit(s: String?) = if (s == null) "null" else "'" + s.replace("'", "''") + "'"
    fun index(n: String) = "${d.quote(schema)}.${d.quote(n)}"
    fun alter(clause: String) = "alter table $q $clause"
    val out = ArrayList<String>()

    val kept = after.columns.mapNotNull { a -> before.columns.find { it.name == a.origin }?.let { a to it } }
    val renamed = kept.filter { (a, b) -> a.name != b.name }.associate { (a, b) -> b.name to a.name }
    fun mapped(l: List<String>) = l.map { renamed[it] ?: it }

    val keys = changes(before.keys, after.keys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val foreignKeys = changes(before.foreignKeys, after.foreignKeys, { it.origin }, { it.name }) { y, x ->
        y.copy(origin = null, name = "", columns = mapped(y.columns)) == x.copy(origin = null, name = "")
    }
    val checks = changes(before.checks, after.checks, { it.origin }, { it.name }) { y, x -> y.expression == x.expression }
    val indexes = changes(before.indexes, after.indexes, { it.origin }, { it.name }) { y, x ->
        val columns = y.columns.map { if (it.expression) it else it.copy(name = renamed[it.name] ?: it.name) }
        y.copy(origin = null, name = "", columns = columns, include = mapped(y.include)) == x.copy(origin = null, name = "")
    }

    // 1. what goes away
    foreignKeys.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    checks.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    keys.dropped.forEach { out += alter("drop constraint ${id(it.name)}") }
    indexes.dropped.forEach { out += "drop index ${index(it.name)}" }

    // 2. columns
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }.forEach { out += alter("drop column ${id(it.name)}") }
    for (c in after.columns.filter { it.origin == null }) {
        val ident = c.identity
        val gen = c.generated
        val def = c.default
        out += alter("add column " + buildString {
            append(id(c.name), " ", c.type)
            when {
                gen != null -> append(" generated always as ($gen) stored")
                ident != null -> append(" generated ", if (ident.always) "always" else "by default", " as identity", identityOptions(ident))
                def != null -> append(" default ", def)
            }
            if (c.notNull) append(" not null")
        })
        c.comment?.let { out += "comment on column $q.${id(c.name)} is ${lit(it)}" }
    }
    for ((a, b) in kept) {
        val c = id(a.name)
        if (a.name != b.name) out += alter("rename column ${id(b.name)} to $c")
        val retyped = a.type != b.type
        if (retyped && b.default != null) out += alter("alter column $c drop default")
        if (retyped) out += alter("alter column $c type ${a.type} using $c::${a.type}")
        if (a.notNull != b.notNull) out += alter("alter column $c ${if (a.notNull) "set" else "drop"} not null")
        val def = a.default
        if (def != null && (retyped || def != b.default)) out += alter("alter column $c set default $def")
        else if (def == null && b.default != null && !retyped) out += alter("alter column $c drop default")
        val ai = a.identity
        val bi = b.identity
        when {
            ai == null && bi != null -> out += alter("alter column $c drop identity")
            ai != null && bi == null -> out += alter("alter column $c add generated ${if (ai.always) "always" else "by default"} as identity${identityOptions(ai)}")
            ai != null && bi != null -> {
                if (ai.always != bi.always) out += alter("alter column $c set generated ${if (ai.always) "always" else "by default"}")
                if (ai.step != bi.step && ai.step != null) out += alter("alter column $c set increment by ${ai.step}")
                if (ai.min != bi.min) out += alter("alter column $c " + (ai.min?.let { "set minvalue $it" } ?: "set no minvalue"))
                if (ai.max != bi.max) out += alter("alter column $c " + (ai.max?.let { "set maxvalue $it" } ?: "set no maxvalue"))
                if (ai.cache != bi.cache && ai.cache != null) out += alter("alter column $c set cache ${ai.cache}")
                if (ai.cycle != bi.cycle) out += alter("alter column $c set ${if (ai.cycle) "cycle" else "no cycle"}")
            }
        }
        if (a.comment != b.comment) out += "comment on column $q.$c is ${lit(a.comment)}"
    }

    // 3. what is new or only renamed
    keys.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    checks.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    foreignKeys.renamed.forEach { (y, x) -> out += alter("rename constraint ${id(y.name)} to ${id(x.name)}") }
    indexes.renamed.forEach { (y, x) -> out += "alter index ${index(y.name)} rename to ${id(x.name)}" }
    keys.created.forEach { k ->
        out += alter("add constraint ${id(k.name)} ${if (k.primary) "primary key" else "unique"} (${ids(k.columns)})${deferral(k.deferrable, k.initiallyDeferred)}")
    }
    checks.created.forEach { out += alter("add constraint ${id(it.name)} check (${it.expression})") }
    indexes.created.forEach { i ->
        val columns = i.columns.joinToString(", ") { c ->
            buildString {
                append(if (c.expression) "(${c.name})" else id(c.name))
                c.collation?.let { append(" collate ", id(it)) }
                c.opClass?.let { append(" ", it) }
                if (c.order.isNotEmpty()) append(" ", c.order)
            }
        }
        out += "create ${if (i.unique) "unique " else ""}index ${id(i.name)} on $q using ${i.method} ($columns)" +
            (if (i.include.isNotEmpty()) " include (${ids(i.include)})" else "") + (i.condition?.let { " where $it" } ?: "")
    }
    foreignKeys.created.forEach { f ->
        val ref = d.qualified(DbObject(f.refNs, f.refTable, DbObjectKind.TABLE))
        out += alter("add constraint ${id(f.name)} foreign key (${ids(f.columns)}) references $ref (${ids(f.refColumns)}) on delete ${f.onDelete} on update ${f.onUpdate}${deferral(f.deferrable, f.initiallyDeferred)}")
    }

    // 4. the table itself, last so everything above uses the name it has now
    if (after.comment != before.comment) out += "comment on table $q is ${lit(after.comment)}"
    if (after.name != before.name) out += alter("rename to ${id(after.name)}")
    return out
}
```

En `PostgresDialect.kt` añadir dentro del objeto, junto a `tableModel`:

```kotlin
    override fun alterTable(o: DbObject, before: TableModel, after: TableModel): List<String> = postgresAlterTable(this, o, before, after)
```

- [ ] **Step 5: Escribir las reglas y la aplicación atómica**

`drivers/src/main/kotlin/ksqlc/drivers/TableModelRules.kt`:

```kotlin
package ksqlc.drivers

import ksqlc.core.*

/** Why [m] cannot be applied yet, or null when it can. */
fun tableProblem(m: TableModel): String? {
    if (m.name.isBlank()) return "La tabla necesita un nombre"
    if (m.columns.isEmpty()) return "La tabla necesita al menos una columna"
    for (c in m.columns) {
        if (c.name.isBlank()) return "Una columna no tiene nombre"
        if (c.type.isBlank()) return "La columna ${c.name} no tiene tipo"
        if (c.identity != null && !c.notNull) return "La columna ${c.name} es identity y tiene que ser not null"
    }
    val names = m.columns.map { it.name.lowercase() }
    names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "La columna ${it.key} está repetida" }
    fun missing(cols: List<String>) = cols.firstOrNull { it.lowercase() !in names }
    if (m.keys.count { it.primary } > 1) return "Solo puede haber una clave primaria"
    for (k in m.keys) {
        if (k.name.isBlank()) return "Una clave no tiene nombre"
        if (k.columns.isEmpty()) return "La clave ${k.name} no tiene columnas"
        missing(k.columns)?.let { return "La clave ${k.name} usa la columna $it, que no existe" }
    }
    for (f in m.foreignKeys) {
        if (f.name.isBlank()) return "Una clave foránea no tiene nombre"
        if (f.columns.isEmpty()) return "La clave foránea ${f.name} no tiene columnas"
        missing(f.columns)?.let { return "La clave foránea ${f.name} usa la columna $it, que no existe" }
        if (f.refTable.isBlank()) return "La clave foránea ${f.name} no dice a qué tabla apunta"
        if (f.refColumns.size != f.columns.size) return "La clave foránea ${f.name} necesita tantas columnas referidas como propias"
    }
    for (c in m.checks) {
        if (c.name.isBlank()) return "Un check no tiene nombre"
        if (c.expression.isBlank()) return "El check ${c.name} no tiene expresión"
    }
    for (i in m.indexes) {
        if (i.name.isBlank()) return "Un índice no tiene nombre"
        if (i.columns.isEmpty() || i.columns.any { it.name.isBlank() }) return "El índice ${i.name} tiene una columna vacía o ninguna"
        missing(i.columns.filter { !it.expression }.map { it.name } + i.include)?.let { return "El índice ${i.name} usa la columna $it, que no existe" }
    }
    (m.keys.map { it.name } + m.foreignKeys.map { it.name } + m.checks.map { it.name }).groupBy { it.lowercase() }.entries
        .firstOrNull { it.value.size > 1 }?.let { return "El nombre de restricción ${it.key} está repetido" }
    return null
}

/** What applying [after] over [before] would lose, one sentence each: dropped columns and changed types. */
fun destructiveChanges(before: TableModel, after: TableModel): List<String> = buildList {
    before.columns.filter { b -> after.columns.none { it.origin == b.name } }
        .forEach { add("Se elimina la columna ${it.name} con todos sus datos.") }
    for (a in after.columns) {
        val b = before.columns.find { it.name == a.origin } ?: continue
        if (b.type != a.type) add("Se cambia el tipo de la columna ${a.name} de ${b.type} a ${a.type}; los valores que no quepan darán error o se perderán.")
    }
}
```

`drivers/src/main/kotlin/ksqlc/drivers/TableApply.kt`:

```kotlin
package ksqlc.drivers

import ksqlc.core.DataSource
import ksqlc.core.StatementResult

/** Runs [statements] in one transaction on a connection of its own: null when all went through, else the error of the first that failed (everything undone). */
fun runAtomically(ds: DataSource, password: String?, statements: List<String>): String? =
    Session(ds, password, autoCommit = false).use { s ->
        for (sql in statements) {
            val r = s.runScript(listOf(sql)).single()
            if (r is StatementResult.Failed) { s.rollback(); return "${r.message}\n$sql" }
        }
        s.commit()
        null
    }
```

- [ ] **Step 6: Ejecutar los tests unitarios**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.TableAlterTest' --tests 'ksqlc.drivers.TableModelRulesTest' -q`
Expected: todos pasan. Si algún orden de sentencias difiere del esperado, el esperado es el que manda (está derivado de la spec): corregir el generador.

- [ ] **Step 7: Escribir el test contra servidor**

`drivers/src/test/kotlin/ksqlc/drivers/server/TableAlterServerTest.kt`:

```kotlin
package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.runAtomically
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.*

class TableAlterServerTest {
    companion object {
        private var container: PostgreSQLContainer? = null
        private lateinit var fx: ServerFixture

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            container = c
            fx = fixture(DbKind.POSTGRES, c.jdbcUrl, c.username, c.password, listOf(
                "create schema at",
                "create table at.parent(id int primary key)",
                """create table at.t(
                     id serial primary key,
                     name text not null default 'x',
                     parent_id int,
                     price numeric(10,2),
                     constraint t_price_ck check (price > 0))""",
                "create index t_name_idx on at.t (name)",
                "create table at.u(id int)",
                "insert into at.t(name, price) values ('a', 1), ('b', 2)",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private fun obj(name: String) = DbObject(Namespace(null, "at"), name, DbObjectKind.TABLE)
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun model(name: String) = intro().use { it.tableModel(obj(name))!! }
    private fun statements(name: String, f: (TableModel) -> TableModel): Pair<TableModel, List<String>> {
        val before = model(name)
        return before to intro().use { it.dialect.alterTable(obj(name), before, f(before)) }
    }

    @Test fun aLoadedModelProducesNoStatements() {
        for (t in listOf("t", "parent", "u")) assertEquals(emptyList(), statements(t) { it }.second, t)
    }

    @Test fun appliesRenamesTypesDefaultsConstraintsAndIndexesAtOnce() {
        val (_, sql) = statements("u") { m ->
            m.copy(
                comment = "tabla u",
                columns = listOf(
                    m.columns[0].copy(name = "uid", notNull = true, type = "bigint"),
                    ColumnModel(null, "label", "text", default = "'n'", comment = "etiqueta"),
                    ColumnModel(null, "ref", "int"),
                ),
                keys = listOf(KeyModel(null, "u_pkey", true, listOf("uid"))),
                checks = listOf(CheckModel(null, "u_label_ck", "(length(label) > 0)")),
                indexes = listOf(IndexModel(null, "u_label_idx", false, "btree", listOf(IndexColumn("label", order = "DESC")), condition = "(label <> 'z')")),
                foreignKeys = listOf(ForeignKeyModel(null, "u_ref_fk", listOf("ref"), Namespace(null, "at"), "parent", listOf("id"), onDelete = "SET NULL")),
            )
        }
        assertNull(runAtomically(fx.ds, fx.password, sql), sql.joinToString("\n"))
        val m = model("u")
        assertEquals("tabla u", m.comment)
        assertEquals(listOf("uid", "label", "ref"), m.columns.map { it.name })
        assertEquals("bigint", m.columns[0].type); assertTrue(m.columns[0].notNull)
        assertEquals("etiqueta", m.columns[1].comment)
        assertEquals(listOf("u_pkey"), m.keys.map { it.name })
        assertEquals("SET NULL", m.foreignKeys.single().onDelete)
        assertEquals("DESC", m.indexes.single().columns[0].order)
        assertEquals(emptyList(), statements("u") { it }.second)      // what was applied reads back with no difference
    }

    @Test fun renamingAColumnKeepsTheDataTheIndexAndTheCheck() {
        val (_, sql) = statements("t") { m ->
            m.copy(
                columns = m.columns.map { if (it.name == "name") it.copy(name = "title") else it },
                indexes = m.indexes.map { ix -> ix.copy(columns = ix.columns.map { c -> if (c.name == "name") c.copy(name = "title") else c }) },
            )
        }
        assertEquals(1, sql.size, sql.toString())
        assertNull(runAtomically(fx.ds, fx.password, sql))
        val m = model("t")
        assertEquals(listOf("title"), m.indexes.single().columns.map { it.name })
        assertEquals(2, java.sql.DriverManager.getConnection(fx.ds.url, fx.ds.user, fx.password).use { c ->
            c.createStatement().executeQuery("select count(*) from at.t where title is not null").use { it.next(); it.getInt(1) }
        })
    }

    @Test fun aFailureHalfwayLeavesNothingApplied() {
        val before = model("parent")
        val sql = listOf("alter table \"at\".\"parent\" add column extra int", "alter table \"at\".\"parent\" add column extra int")      // the second repeats the name
        val error = runAtomically(fx.ds, fx.password, sql)
        assertNotNull(error); assertTrue(error.contains("extra"), error)
        assertEquals(before.columns.map { it.name }, model("parent").columns.map { it.name })
    }
}
```

- [ ] **Step 8: Ejecutar el test contra servidor**

Run: `./gradlew :drivers:test --tests 'ksqlc.drivers.server.TableAlterServerTest' -q`
Expected: 4 tests pasan. Si el test `appliesRenames…` falla por el orden (p. ej. la clave primaria antes de que `uid` sea not null), ajustar el generador para que `set not null` vaya antes de crear la clave (ya ocurre: la fase de columnas va antes que la de creación). Si falla `renamingAColumn…` porque la columna `name` sigue en el modelo de índice, revisar el mapeo `renamed` del generador.

- [ ] **Step 9: Commit**

```bash
git add drivers/src
git commit -m "Generador de ALTER para tablas de PostgreSQL, validación del modelo y aplicación en una transacción

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Estado del diálogo (`ModifyDraft`)

**Files:**
- Create: `app/src/main/kotlin/ksqlc/app/ModifyDraft.kt`
- Test: `app/src/test/kotlin/ksqlc/app/ModifyDraftTest.kt`

**Interfaces:**
- Consumes: `TableModel` y sus datos; `Dialect.alterTable`; `tableProblem`.
- Produces: `data class Sel(val group: String, val index: Int = -1)`; `class ModifyDraft(obj: DbObject, before: TableModel, dialect: Dialect)` con `model`, `selected`, `problem`, `statements`, `rename(name)`, `comment(text)`, `updateColumn(i, f)`, `updateKey(i, f)`, `updateForeignKey(i, f)`, `updateIndex(i, f)`, `updateCheck(i, f)`, `add(group)`, `remove(sel)`, `duplicate(sel)`; helpers `internal fun <T> List<T>.replaced(i: Int, v: T)` y `internal fun <T> List<T>.moved(from: Int, to: Int)`.

- [ ] **Step 1: Escribir el test que falla**

`app/src/test/kotlin/ksqlc/app/ModifyDraftTest.kt`:

```kotlin
package ksqlc.app

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import kotlin.test.*

class ModifyDraftTest {
    private val ns = Namespace(null, "app")
    private val tbl = DbObject(ns, "t", DbObjectKind.TABLE)
    private val before = TableModel(
        "t", null,
        columns = listOf(ColumnModel("id", "id", "bigint", true), ColumnModel("name", "name", "text")),
        keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id"))),
        indexes = listOf(IndexModel("t_name_idx", "t_name_idx", false, "btree", listOf(IndexColumn("name")))),
    )
    private fun draft() = ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES))

    @Test fun untouchedMeansNoStatements() = draft().let { assertNull(it.problem); assertEquals(emptyList(), it.statements) }

    @Test fun renamingAColumnRewritesItsReferencesAndOnlyRenames() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "title") }
        assertEquals("title", d.model.indexes[0].columns[0].name)
        assertEquals(listOf("alter table \"app\".\"t\" rename column \"name\" to \"title\""), d.statements)
        d.updateColumn(0) { it.copy(name = "pk") }
        assertEquals(listOf("pk"), d.model.keys[0].columns)
    }

    @Test fun addingColumnsGivesThemUniqueNamesAndSelectsTheNewOne() {
        val d = draft()
        d.add("columns"); d.add("columns")
        assertEquals(listOf("id", "name", "column_name", "column_name_2"), d.model.columns.map { it.name })
        assertEquals(Sel("columns", 3), d.selected)
        assertEquals(2, d.statements.size)
        d.remove(Sel("columns", 3)); d.remove(Sel("columns", 2))
        assertEquals(emptyList(), d.statements)
        assertEquals(Sel("columns"), d.selected)
    }

    @Test fun addingKeysChecksIndexesAndForeignKeysStartOnTheFirstColumn() {
        val d = draft()
        d.add("keys"); d.add("checks"); d.add("indexes"); d.add("fks")
        assertEquals(listOf("id"), d.model.keys.last().columns)
        assertEquals(false, d.model.keys.last().primary)
        assertEquals("t_key", d.model.keys.last().name)
        assertEquals(listOf("id"), d.model.indexes.last().columns.map { it.name })
        assertEquals("t_idx", d.model.indexes.last().name)
        assertEquals(listOf("id"), d.model.foreignKeys.last().columns)
        assertNotNull(d.problem)      // the new check has no expression and the foreign key no table
        assertEquals(emptyList(), d.statements)
    }

    @Test fun duplicatingCopiesWithoutOriginAndANewName() {
        val d = draft()
        d.duplicate(Sel("columns", 1))
        val copy = d.model.columns[2]
        assertNull(copy.origin); assertEquals("name_copy", copy.name); assertEquals("text", copy.type)
        d.duplicate(Sel("columns", 1))
        assertEquals("name_copy_2", d.model.columns[3].name)
    }

    @Test fun aProblemBlocksTheStatements() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "id") }
        assertNotNull(d.problem); assertEquals(emptyList(), d.statements)
        d.updateColumn(1) { it.copy(name = "name") }
        d.updateKey(0) { it.copy(columns = emptyList()) }
        assertNotNull(d.problem)
    }

    @Test fun listHelpers() {
        assertEquals(listOf(1, 9, 3), listOf(1, 2, 3).replaced(1, 9))
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(0, 1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))      // out of range: unchanged
    }
}
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyDraftTest' -q`
Expected: no compila (`ModifyDraft` no existe).

- [ ] **Step 3: Implementar**

`app/src/main/kotlin/ksqlc/app/ModifyDraft.kt`:

```kotlin
package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.tableProblem

/** The selected node of the Modify tree: [group] is "table", "columns", "keys", "fks", "indexes" or "checks"; [index] is -1 on the table and group rows. */
data class Sel(val group: String, val index: Int = -1)

internal fun <T> List<T>.replaced(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (to !in indices || from !in indices) this else toMutableList().also { it.add(to, it.removeAt(from)) }

/** The table being modified: what the dialog edits, and the SQL that editing amounts to. */
class ModifyDraft(val obj: DbObject, val before: TableModel, private val dialect: Dialect) {
    var model by mutableStateOf(before)
        private set
    var selected by mutableStateOf(Sel("table"))

    val problem: String? get() = tableProblem(model)

    /** The SQL OK would run; empty while there is a [problem] or nothing changed. */
    val statements: List<String> get() = if (problem != null) emptyList() else dialect.alterTable(obj, before, model)

    fun rename(name: String) { model = model.copy(name = name) }
    fun comment(text: String) { model = model.copy(comment = text.ifBlank { null }) }

    /** Renaming a column rewrites the keys, foreign keys and indexes that use it, so they stay the same element. */
    fun updateColumn(i: Int, f: (ColumnModel) -> ColumnModel) {
        val old = model.columns[i]
        val new = f(old)
        var m = model.copy(columns = model.columns.replaced(i, new))
        if (new.name != old.name) {
            fun r(l: List<String>) = l.map { if (it == old.name) new.name else it }
            m = m.copy(
                keys = m.keys.map { it.copy(columns = r(it.columns)) },
                foreignKeys = m.foreignKeys.map { it.copy(columns = r(it.columns)) },
                indexes = m.indexes.map { ix ->
                    ix.copy(columns = ix.columns.map { c -> if (c.expression || c.name != old.name) c else c.copy(name = new.name) }, include = r(ix.include))
                },
            )
        }
        model = m
    }

    fun updateKey(i: Int, f: (KeyModel) -> KeyModel) { model = model.copy(keys = model.keys.replaced(i, f(model.keys[i]))) }
    fun updateForeignKey(i: Int, f: (ForeignKeyModel) -> ForeignKeyModel) { model = model.copy(foreignKeys = model.foreignKeys.replaced(i, f(model.foreignKeys[i]))) }
    fun updateIndex(i: Int, f: (IndexModel) -> IndexModel) { model = model.copy(indexes = model.indexes.replaced(i, f(model.indexes[i]))) }
    fun updateCheck(i: Int, f: (CheckModel) -> CheckModel) { model = model.copy(checks = model.checks.replaced(i, f(model.checks[i]))) }

    private fun unique(base: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in used) return base
        var n = 2
        while ("${base}_$n".lowercase() in used) n++
        return "${base}_$n"
    }

    private fun constraintNames() = model.keys.map { it.name } + model.foreignKeys.map { it.name } + model.checks.map { it.name } + model.indexes.map { it.name }

    fun add(group: String) {
        val first = listOfNotNull(model.columns.firstOrNull()?.name)
        val t = model.name
        when (group) {
            "columns" -> model = model.copy(columns = model.columns + ColumnModel(null, unique("column_name", model.columns.map { it.name }), "integer"))
            "keys" -> model = model.copy(keys = model.keys + KeyModel(null, unique("${t}_key", constraintNames()), false, first))
            "fks" -> model = model.copy(foreignKeys = model.foreignKeys + ForeignKeyModel(null, unique("${t}_fk", constraintNames()), first, obj.ns, "", emptyList()))
            "indexes" -> model = model.copy(indexes = model.indexes + IndexModel(null, unique("${t}_idx", constraintNames()), false, "btree", first.map { IndexColumn(it) }))
            "checks" -> model = model.copy(checks = model.checks + CheckModel(null, unique("${t}_check", constraintNames()), ""))
            else -> return
        }
        selected = Sel(group, count(group) - 1)
    }

    private fun count(group: String) = when (group) {
        "columns" -> model.columns.size; "keys" -> model.keys.size; "fks" -> model.foreignKeys.size
        "indexes" -> model.indexes.size; "checks" -> model.checks.size; else -> 0
    }

    private fun <T> List<T>.without(i: Int) = filterIndexed { n, _ -> n != i }

    fun remove(sel: Sel) {
        val i = sel.index
        if (i < 0 || i >= count(sel.group)) return
        model = when (sel.group) {
            "columns" -> model.copy(columns = model.columns.without(i))
            "keys" -> model.copy(keys = model.keys.without(i))
            "fks" -> model.copy(foreignKeys = model.foreignKeys.without(i))
            "indexes" -> model.copy(indexes = model.indexes.without(i))
            else -> model.copy(checks = model.checks.without(i))
        }
        selected = Sel(sel.group)
    }

    fun duplicate(sel: Sel) {
        val i = sel.index
        if (i < 0 || i >= count(sel.group)) return
        when (sel.group) {
            "columns" -> model.columns[i].let { c -> model = model.copy(columns = model.columns + c.copy(origin = null, name = unique("${c.name}_copy", model.columns.map { it.name }))) }
            "keys" -> model.keys[i].let { k -> model = model.copy(keys = model.keys + k.copy(origin = null, primary = false, name = unique("${k.name}_copy", constraintNames()))) }
            "fks" -> model.foreignKeys[i].let { f -> model = model.copy(foreignKeys = model.foreignKeys + f.copy(origin = null, name = unique("${f.name}_copy", constraintNames()))) }
            "indexes" -> model.indexes[i].let { x -> model = model.copy(indexes = model.indexes + x.copy(origin = null, name = unique("${x.name}_copy", constraintNames()))) }
            else -> model.checks[i].let { c -> model = model.copy(checks = model.checks + c.copy(origin = null, name = unique("${c.name}_copy", constraintNames()))) }
        }
        selected = Sel(sel.group, count(sel.group) - 1)
    }
}
```

- [ ] **Step 4: Ejecutar el test**

Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyDraftTest' -q`
Expected: 7 tests pasan.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ksqlc/app/ModifyDraft.kt app/src/test/kotlin/ksqlc/app/ModifyDraftTest.kt
git commit -m "Estado del diálogo de modificar tabla: edición, selección y SQL resultante

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Diálogo, aplicar y menú

**Files:**
- Create: `app/src/main/kotlin/ksqlc/app/ModifyDialog.kt`
- Modify: `app/src/main/kotlin/ksqlc/app/AppState.kt`, `app/src/main/kotlin/ksqlc/app/ExplorerPanel.kt`, `app/src/main/kotlin/ksqlc/app/MainWindow.kt`
- Test: `app/src/test/kotlin/ksqlc/app/ModifyUiTest.kt`

**Interfaces:**
- Consumes: `ModifyDraft`, `Sel`, `replaced`, `moved` (Task 3); `runAtomically`, `destructiveChanges`, `supportsModifyTable` (Tasks 1–2); `SchemaSource.tableModel`.
- Produces: `AppState.modifyDraft`, `modifyError`, `modifyBusy`, `openModify(o)`, `closeModify()`, `applyModify()`; `@Composable fun ModifyDialog(state: AppState)`.

- [ ] **Step 1: Escribir el test de interfaz (falla)**

`app/src/test/kotlin/ksqlc/app/ModifyUiTest.kt`:

```kotlin
package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DbKind
import ksqlc.core.DbObject
import ksqlc.core.DbObjectKind
import ksqlc.core.Namespace
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Modify dialog on a real PostgreSQL: edit, read the preview, apply, and see the table changed. */
@OptIn(ExperimentalTestApi::class)
class ModifyUiTest {
    companion object {
        private var pg: PostgreSQLContainer? = null

        @BeforeClass @JvmStatic fun start() {
            assumeTrue("Docker no disponible", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
            pg = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
            java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
                c.createStatement().execute("create table public.mt(id int primary key, name text)")
            }
        }

        @AfterClass @JvmStatic fun stop() { pg?.stop() }
    }

    @get:Rule val rule = createComposeRule()

    private val state = AppState(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ConnectionStore(Files.createTempDirectory("ksqlc-ui-mod"), InMemorySecretStore()),
        SettingsStore(Files.createTempDirectory("ksqlc-ui-mods")),
    )

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun until(cond: () -> Boolean) {
        val end = System.nanoTime() + 40_000_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timeout (status='${state.status}')" }; Thread.sleep(50); rule.mainClock.advanceTimeByFrame() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
    }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun frames() = repeat(3) { rule.mainClock.advanceTimeByFrame() }
    private fun columns() = java.sql.DriverManager.getConnection(pg!!.jdbcUrl, pg!!.username, pg!!.password).use { c ->
        c.createStatement().executeQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'mt' order by ordinal_position")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun open() {
        rule.setContent { App(state) }
        val f = state.form
        f.kind = DbKind.POSTGRES; f.host = pg!!.host; f.portText = pg!!.firstMappedPort.toString(); f.database = pg!!.databaseName; f.user = pg!!.username; f.password = pg!!.password
        state.connect()
        until { state.explorer != null }
        state.openModify(DbObject(Namespace(null, "public"), "mt", DbObjectKind.TABLE))
        until { exists("modify-dialog") }
    }

    @Test fun renameAColumnAddAnotherSeeThePreviewAndApply() {
        open()
        rule.onNodeWithTag("modify-ok").assertIsNotEnabled()      // nothing changed yet
        rule.onNodeWithTag("modify:columns:1").performClick(); frames()
        rule.onNodeWithTag("modify-col-name").performTextReplacement("nombre"); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("rename column \"name\" to \"nombre\"", substring = true)
        rule.onNodeWithTag("modify:columns:-1").performClick(); frames()
        rule.onNodeWithTag("modify-add").performClick(); frames()
        rule.onNodeWithTag("modify-preview").assertTextContains("add column \"column_name\" integer", substring = true)
        rule.onNodeWithTag("modify-ok").assertIsEnabled().performClick()
        until { state.modifyDraft == null }
        assertEquals(listOf("id", "nombre", "column_name"), columns())
        assertNull(state.modifyError)
    }

    @Test fun anInvalidModelDisablesOkAndShowsWhy() {
        open()
        rule.onNodeWithTag("modify:table:-1").performClick(); frames()
        rule.onNodeWithTag("modify-name").performTextReplacement(""); frames()
        rule.onNodeWithTag("modify-ok").assertIsNotEnabled()
        rule.onNodeWithTag("modify-preview").assertTextContains("necesita un nombre", substring = true)
        rule.onNodeWithTag("modify-cancel").performClick()
        until { state.modifyDraft == null }
    }
}
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyUiTest' -q`
Expected: no compila (`openModify`, `modifyDraft`, `modifyError` no existen).

- [ ] **Step 3: Estado y acciones en `AppState`**

Añadir a `AppState.kt` (junto a `callRoutine`), con los imports `ksqlc.drivers.destructiveChanges` y `ksqlc.drivers.runAtomically`:

```kotlin
    /** The Modify dialog's table while it is open. */
    var modifyDraft by mutableStateOf<ModifyDraft?>(null)
    var modifyError by mutableStateOf<String?>(null)
    var modifyBusy by mutableStateOf(false)

    fun openModify(o: DbObject) {
        val m = explorer ?: return
        status = "Leyendo ${o.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { m.source.tableModel(o) ?: error("Este motor todavía no permite modificar tablas") } }
                .onSuccess { modifyError = null; modifyDraft = ModifyDraft(o, it, m.source.dialect) }
                .onFailure { status = "No se pudo leer ${o.name}: ${it.message}" }
        }
    }

    fun closeModify() { modifyDraft = null; modifyError = null }

    /** OK in the Modify dialog: asks first when something loses data, then runs every statement in one transaction. */
    fun applyModify() {
        val d = modifyDraft ?: return
        val statements = d.statements
        if (statements.isEmpty() || modifyBusy) return
        val losses = destructiveChanges(d.before, d.model)
        if (losses.isEmpty()) runModify(statements)
        else confirmation = Confirmation("Cambios que pierden datos", losses.joinToString("\n"), "Aplicar") { confirmation = null; runModify(statements) }
    }

    private fun runModify(statements: List<String>) {
        val target = form.toDataSource(); val pw = form.passwordOrNull
        modifyBusy = true; modifyError = null
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { runAtomically(target, pw, statements) }.getOrElse { it.message ?: it.toString() } }
            val now = System.currentTimeMillis()
            record(target.id, statements.map { HistoryEntry(now, "$it;", 0, ok = error == null) })
            modifyBusy = false
            if (error != null) { modifyError = error; return@launch }
            closeModify()
            status = "Tabla modificada"
            refreshExplorer()
        }
    }
```

- [ ] **Step 4: Hacer visible `TreeLine`, menú y montaje**

En `ExplorerPanel.kt`: cambiar `private fun TreeLine(` por `internal fun TreeLine(`. Añadir el import `ksqlc.drivers.supportsModifyTable` junto a los demás de `ksqlc.drivers` y, dentro del bloque `if (o != null) {`, antes de `add(ContextMenuItem("Comparar esquema con…")`:

```kotlin
                    if (node.kind == NodeKind.TABLE && supportsModifyTable(state.form.effectiveKind) && !state.form.options.readOnly) add(ContextMenuItem("Modificar…") { state.openModify(o) })
```

En `MainWindow.kt`, tras `DdlDialog(state)`:

```kotlin
        ModifyDialog(state)
```

- [ ] **Step 5: Escribir el diálogo**

`app/src/main/kotlin/ksqlc/app/ModifyDialog.kt`. Copiar el bloque de imports de `Dialogs.kt` (foundation, layout, material3 `DropdownMenu`/`DropdownMenuItem`/`Text`, runtime, ui, `ksqlc.app.ui.*`) y quitar los que sobren, más `ksqlc.core.*`:

```kotlin
package ksqlc.app

// imports: los de Dialogs.kt que se usen, y ksqlc.core.*, androidx.compose.ui.text.font.FontFamily

private fun String.orNull() = trim().ifEmpty { null }
private val ACTIONS = listOf("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT")
private val METHODS = listOf("btree", "hash", "gist", "spgist", "gin", "brin")

/** "Modify" for a table: the tree of its parts on the left, the form of the selected one on the right, the SQL it amounts to below. */
@Composable
fun ModifyDialog(state: AppState) {
    val draft = state.modifyDraft ?: return
    val c = Ksqlc.colors
    val sql = draft.statements
    val preview = draft.problem ?: if (sql.isEmpty()) "-- sin cambios" else sql.joinToString("\n") { "$it;" }
    ModalOverlay(state::closeModify, Modifier.testTag("modify-dialog"), width = 920.dp, height = 720.dp) {
        Column(Modifier.fillMaxSize().background(c.dialogBg)) {
            Box(Modifier.fillMaxWidth().height(29.dp), contentAlignment = Alignment.Center) {
                Text("Modificar ${draft.obj.name}", fontSize = 13.sp, color = c.textSecondary)
            }
            Separator()
            Row(Modifier.weight(1f)) {
                ModifyTree(draft, Modifier.width(290.dp).fillMaxHeight())
                Separator(vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { ModifyForm(draft) }
            }
            Separator()
            Text("Vista previa", Modifier.padding(start = 12.dp, top = 6.dp), fontSize = 12.sp, color = c.textSecondary)
            Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
                Text(preview, Modifier.testTag("modify-preview"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (draft.problem != null) c.error else c.text)
            }
            state.modifyError?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("modify-error"), fontSize = 12.sp, color = c.error) }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction("Cancelar", state::closeModify, Modifier.testTag("modify-cancel"))
                TextAction("OK", state::applyModify, Modifier.testTag("modify-ok"), enabled = sql.isNotEmpty() && !state.modifyBusy, primary = true)
            }
        }
    }
}

@Composable
private fun ModifyTree(draft: ModifyDraft, modifier: Modifier) {
    val m = draft.model
    val sel = draft.selected
    @Composable fun node(s: Sel, depth: Int, icon: IconKind, label: String, detail: String?) {
        Box(Modifier.clickable { draft.selected = s }.testTag("modify:${s.group}:${s.index}")) {
            TreeLine(depth, expandable = false, expanded = true, icon = icon, label = label, detail = detail, selected = sel == s)
        }
    }
    @Composable fun group(g: String, title: String, count: Int, icon: IconKind, items: List<Pair<String, String?>>) {
        node(Sel(g), 1, IconKind.Folder, "$title $count", null)
        items.forEachIndexed { i, (label, detail) -> node(Sel(g, i), 2, icon, label, detail) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(IconKind.Plus, "Añadir", { draft.add(sel.group) }, Modifier.testTag("modify-add"), enabled = sel.group != "table")
            ToolButton(IconKind.Minus, "Quitar", { draft.remove(sel) }, Modifier.testTag("modify-remove"), enabled = sel.index >= 0)
            ToolButton(IconKind.Copy, "Duplicar", { draft.duplicate(sel) }, Modifier.testTag("modify-duplicate"), enabled = sel.index >= 0)
        }
        Separator()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            node(Sel("table"), 0, IconKind.Table, m.name.ifEmpty { "(sin nombre)" }, null)
            group("columns", "columnas", m.columns.size, IconKind.Column, m.columns.map { it.name to it.type })
            group("keys", "claves", m.keys.size, IconKind.Key, m.keys.map { it.name to (if (it.primary) "primaria " else "") + "(${it.columns.joinToString()})" })
            group("fks", "claves foráneas", m.foreignKeys.size, IconKind.ForeignKey, m.foreignKeys.map { it.name to "(${it.columns.joinToString()}) → ${it.refTable}" })
            group("indexes", "índices", m.indexes.size, IconKind.Index, m.indexes.map { it.name to (if (it.unique) "único " else "") + "(${it.columns.joinToString { c -> c.name }})" })
            group("checks", "checks", m.checks.size, IconKind.Check, m.checks.map { it.name to it.expression })
        }
    }
}

@Composable
private fun MField(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(140.dp).padding(top = 6.dp), fontSize = 13.sp, color = Ksqlc.colors.text)
        content()
    }
}

@Composable
private fun FText(value: String, onChange: (String) -> Unit, tag: String, placeholder: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) =
    CompactTextField(value, onChange, modifier, placeholder = placeholder, enabled = enabled, testTag = tag, height = 28.dp)

@Composable
private fun ChoiceField(value: String, options: List<String>, onChange: (String) -> Unit, tag: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        DropdownField(value, { open = true }, Modifier.fillMaxWidth().testTag(tag))
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o, fontSize = 13.sp) }, modifier = Modifier.testTag("$tag:$o"), onClick = { open = false; onChange(o) }) }
        }
    }
}

/** Names one per row, with up, down and remove; [choices] (when given) are picked from a menu, else typed. */
@Composable
private fun StringListEditor(items: List<String>, onChange: (List<String>) -> Unit, choices: List<String>?, tag: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (choices != null) ChoiceField(item.ifEmpty { "—" }, choices, { onChange(items.replaced(i, it)) }, "$tag:$i", Modifier.weight(1f))
                else FText(item, { onChange(items.replaced(i, it)) }, "$tag:$i", modifier = Modifier.weight(1f))
                ToolButton(IconKind.ArrowUp, "Subir", { onChange(items.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, "Bajar", { onChange(items.moved(i, i + 1)) }, enabled = i < items.lastIndex)
                ToolButton(IconKind.Minus, "Quitar", { onChange(items.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, "Añadir", { onChange(items + (choices?.firstOrNull { it !in items } ?: "")) }, Modifier.testTag("$tag:add"))
    }
}

@Composable
private fun IndexColumnsEditor(cols: List<IndexColumn>, columnNames: List<String>, onChange: (List<IndexColumn>) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cols.forEachIndexed { i, ic ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FText(ic.name, { v -> onChange(cols.replaced(i, ic.copy(name = v, expression = v.trim() !in columnNames))) }, "modify-ix-col:$i", "columna o expresión", modifier = Modifier.weight(1f))
                ChoiceField(ic.order.ifEmpty { "—" }, listOf("—", "ASC", "DESC"), { v -> onChange(cols.replaced(i, ic.copy(order = if (v == "—") "" else v))) }, "modify-ix-order:$i", Modifier.width(84.dp))
                FText(ic.collation.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(collation = v.orNull()))) }, "modify-ix-coll:$i", "colación", modifier = Modifier.width(90.dp))
                FText(ic.opClass.orEmpty(), { v -> onChange(cols.replaced(i, ic.copy(opClass = v.orNull()))) }, "modify-ix-opc:$i", "clase op.", modifier = Modifier.width(90.dp))
                ToolButton(IconKind.ArrowUp, "Subir", { onChange(cols.moved(i, i - 1)) }, enabled = i > 0)
                ToolButton(IconKind.ArrowDown, "Bajar", { onChange(cols.moved(i, i + 1)) }, enabled = i < cols.lastIndex)
                ToolButton(IconKind.Minus, "Quitar", { onChange(cols.filterIndexed { n, _ -> n != i }) })
            }
        }
        ToolButton(IconKind.Plus, "Añadir columna", { onChange(cols + IndexColumn(columnNames.firstOrNull { n -> cols.none { it.name == n } } ?: "")) }, Modifier.testTag("modify-ix-col:add"))
    }
}

@Composable
private fun ModifyForm(draft: ModifyDraft) {
    val m = draft.model
    val sel = draft.selected
    val names = m.columns.map { it.name }
    val i = sel.index
    when {
        sel.group == "table" -> {
            MField("Nombre:") { FText(m.name, draft::rename, "modify-name") }
            MField("Comentario:") { FText(m.comment.orEmpty(), draft::comment, "modify-comment") }
        }
        i < 0 -> Text("Elige un elemento de la lista o pulsa + para añadir uno.", fontSize = 13.sp, color = Ksqlc.colors.textSecondary)
        sel.group == "columns" && i < m.columns.size -> ColumnForm(draft, i)
        sel.group == "keys" && i < m.keys.size -> {
            val k = m.keys[i]
            MField("Nombre:") { FText(k.name, { v -> draft.updateKey(i) { it.copy(name = v) } }, "modify-key-name") }
            MField("") { KCheckbox(k.primary, { v -> draft.updateKey(i) { it.copy(primary = v) } }, label = "Primaria", modifier = Modifier.testTag("modify-key-primary")) }
            MField("") { KCheckbox(k.deferrable, { v -> draft.updateKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = "Diferible", modifier = Modifier.testTag("modify-key-deferrable")) }
            MField("") { KCheckbox(k.initiallyDeferred, { v -> draft.updateKey(i) { it.copy(initiallyDeferred = v) } }, label = "Inicialmente diferida", enabled = k.deferrable, modifier = Modifier.testTag("modify-key-initially")) }
            MField("Columnas:") { StringListEditor(k.columns, { v -> draft.updateKey(i) { it.copy(columns = v) } }, names, "modify-key-col") }
        }
        sel.group == "fks" && i < m.foreignKeys.size -> {
            val f = m.foreignKeys[i]
            MField("Nombre:") { FText(f.name, { v -> draft.updateForeignKey(i) { it.copy(name = v) } }, "modify-fk-name") }
            MField("Columnas:") { StringListEditor(f.columns, { v -> draft.updateForeignKey(i) { it.copy(columns = v) } }, names, "modify-fk-col") }
            MField("Esquema referido:") { FText(f.refNs.schema.orEmpty(), { v -> draft.updateForeignKey(i) { it.copy(refNs = Namespace(null, v.orNull())) } }, "modify-fk-schema") }
            MField("Tabla referida:") { FText(f.refTable, { v -> draft.updateForeignKey(i) { it.copy(refTable = v) } }, "modify-fk-table") }
            MField("Columnas referidas:") { StringListEditor(f.refColumns, { v -> draft.updateForeignKey(i) { it.copy(refColumns = v) } }, null, "modify-fk-refcol") }
            MField("On delete:") { ChoiceField(f.onDelete, ACTIONS, { v -> draft.updateForeignKey(i) { it.copy(onDelete = v) } }, "modify-fk-delete") }
            MField("On update:") { ChoiceField(f.onUpdate, ACTIONS, { v -> draft.updateForeignKey(i) { it.copy(onUpdate = v) } }, "modify-fk-update") }
            MField("") { KCheckbox(f.deferrable, { v -> draft.updateForeignKey(i) { it.copy(deferrable = v, initiallyDeferred = it.initiallyDeferred && v) } }, label = "Diferible", modifier = Modifier.testTag("modify-fk-deferrable")) }
        }
        sel.group == "indexes" && i < m.indexes.size -> {
            val x = m.indexes[i]
            MField("Nombre:") { FText(x.name, { v -> draft.updateIndex(i) { it.copy(name = v) } }, "modify-ix-name") }
            MField("") { KCheckbox(x.unique, { v -> draft.updateIndex(i) { it.copy(unique = v) } }, label = "Único", modifier = Modifier.testTag("modify-ix-unique")) }
            MField("Método de acceso:") { ChoiceField(x.method, METHODS, { v -> draft.updateIndex(i) { it.copy(method = v) } }, "modify-ix-method") }
            MField("Columnas:") { IndexColumnsEditor(x.columns, names) { v -> draft.updateIndex(i) { it.copy(columns = v) } } }
            MField("Incluir columnas:") { StringListEditor(x.include, { v -> draft.updateIndex(i) { it.copy(include = v) } }, names, "modify-ix-inc") }
            MField("Condición:") { FText(x.condition.orEmpty(), { v -> draft.updateIndex(i) { it.copy(condition = v.orNull()) } }, "modify-ix-cond", "p. ej. (activo)") }
        }
        sel.group == "checks" && i < m.checks.size -> {
            val ck = m.checks[i]
            MField("Nombre:") { FText(ck.name, { v -> draft.updateCheck(i) { it.copy(name = v) } }, "modify-ck-name") }
            MField("Expresión:") { FText(ck.expression, { v -> draft.updateCheck(i) { it.copy(expression = v) } }, "modify-ck-expr", "p. ej. (precio > 0)") }
        }
    }
}

@Composable
private fun ColumnForm(draft: ModifyDraft, i: Int) {
    val col = draft.model.columns[i]
    val ident = col.identity
    fun edit(f: (ColumnModel) -> ColumnModel) = draft.updateColumn(i, f)
    fun editIdentity(f: (Identity) -> Identity) = edit { it.copy(identity = it.identity?.let(f)) }
    MField("Nombre:") { FText(col.name, { v -> edit { it.copy(name = v) } }, "modify-col-name") }
    MField("Comentario:") { FText(col.comment.orEmpty(), { v -> edit { it.copy(comment = v.orNull()) } }, "modify-col-comment") }
    MField("Tipo de dato:") { FText(col.type, { v -> edit { it.copy(type = v) } }, "modify-col-type") }
    MField("") { KCheckbox(col.notNull, { v -> edit { it.copy(notNull = v) } }, label = "Not null", enabled = ident == null, modifier = Modifier.testTag("modify-col-notnull")) }
    MField("Valor por defecto:") { FText(col.default.orEmpty(), { v -> edit { it.copy(default = v.orNull()) } }, "modify-col-default", enabled = ident == null && col.generated == null) }
    MField("Generada como:") { FText(col.generated.orEmpty(), { v -> edit { it.copy(generated = v.orNull()) } }, "modify-col-generated", "solo al crear la columna", enabled = col.origin == null && ident == null) }
    MField("") {
        KCheckbox(ident != null, { on -> edit { it.copy(identity = if (on) Identity() else null, notNull = it.notNull || on, default = if (on) null else it.default) } }, label = "Identity", modifier = Modifier.testTag("modify-col-identity"))
    }
    if (ident != null) {
        MField("") { KCheckbox(ident.always, { v -> editIdentity { it.copy(always = v) } }, label = "Siempre (always)", modifier = Modifier.testTag("modify-col-always")) }
        MField("Mínimo:") { FText(ident.min.orEmpty(), { v -> editIdentity { it.copy(min = v.orNull()) } }, "modify-col-min") }
        MField("Máximo:") { FText(ident.max.orEmpty(), { v -> editIdentity { it.copy(max = v.orNull()) } }, "modify-col-max") }
        MField("Paso:") { FText(ident.step.orEmpty(), { v -> editIdentity { it.copy(step = v.orNull()) } }, "modify-col-step") }
        MField("Caché:") { FText(ident.cache.orEmpty(), { v -> editIdentity { it.copy(cache = v.orNull()) } }, "modify-col-cache") }
        MField("") { KCheckbox(ident.cycle, { v -> editIdentity { it.copy(cycle = v) } }, label = "Cíclica", modifier = Modifier.testTag("modify-col-cycle")) }
    }
}
```

- [ ] **Step 6: Compilar y ejecutar**

Run: `./gradlew :app:compileKotlin -q` y corregir imports que falten (por ejemplo `androidx.compose.foundation.clickable`, `verticalScroll`, `rememberScrollState`, `androidx.compose.ui.draw.clip` si se usa).
Run: `./gradlew :app:test --tests 'ksqlc.app.ModifyUiTest' -q`
Expected: 2 tests pasan. Si `performTextReplacement("")` no deja el campo vacío, usar `performTextClearance()` en su lugar.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "Diálogo para modificar tablas: árbol, formularios, vista previa y aplicación en una transacción

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Documentación y suite completa

**Files:**
- Modify: `docs/spec/feature-inventory.md`

- [ ] **Step 1: Añadir la fila al inventario**

Insertar, justo después de la fila `| Ejecutar rutinas | …`, esta fila (misma línea, sin saltos):

```
| Modificar tabla | parcial | «Modificar…» en el menú de una tabla (solo PostgreSQL y con la conexión no de solo lectura): diálogo con árbol (tabla, columnas, claves, claves foráneas, índices, checks), formulario de cada nodo y vista previa del SQL. Columnas: nombre, comentario, tipo, not null, valor por defecto, identity con sus opciones y, al crearlas, columna generada. Claves primaria y únicas (diferibles), claves foráneas con `on delete` y `on update`, índices con método, orden, colación, clase de operador, `include` y condición, y checks. Se aplica todo en una transacción (si algo falla no queda nada), pide confirmación si se pierden datos (quitar columna o cambiar el tipo) y se anota en el historial. Un cambio de definición de una clave, FK, check o índice los quita y los vuelve a crear. No se hicieron persistencia, particiones, opciones de almacenamiento, tablespace, propietario, permisos, restricciones de exclusión, reordenar columnas ni los «virtual» de DataGrip, ni los demás motores (el diálogo y el modelo no dependen del motor; falta leer y generar el SQL de cada uno), ni vistas, secuencias o rutinas. |
```

- [ ] **Step 2: Ejecutar toda la suite**

Run: `./gradlew test -q`
Expected: sin fallos (con Docker activo, ninguna prueba omitida).

- [ ] **Step 3: Commit**

```bash
git add docs/spec/feature-inventory.md
git commit -m "Inventario: modificar tabla

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
