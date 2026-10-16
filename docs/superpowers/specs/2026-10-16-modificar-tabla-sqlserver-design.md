# Modificar una tabla en SQL Server

Fecha: 2026-10-16 · Estado: pendiente de revisión

## Objetivo

«Modificar…» sobre una tabla de una conexión SQL Server abre el mismo diálogo que en PostgreSQL y MySQL (ver `2026-10-15-modificar-tabla-design.md` y `2026-10-16-modificar-tabla-mysql-design.md`), con la vista previa del SQL exacto antes de aplicar. A diferencia de MySQL, **SQL Server tiene DDL transaccional**: se aplica todo o nada, como en PostgreSQL. Lo nuevo es leer el modelo (`sys.*`), generar T-SQL y decidir qué campos se muestran.

## Alcance

Solo **SQL Server 2022** (las pruebas usan `mcr.microsoft.com/mssql/server:2022-latest`, como `SqlServerIntrospectionTest`). Sin ramas para versiones anteriores.

Incluye:
- Tabla: nombre y comentario.
- Columnas: añadir, quitar, renombrar, tipo, not null, default, comentario, identity (solo al crear la columna) y columna calculada (normal o `persisted`, solo al crearla).
- Clave primaria y claves únicas (constraints).
- Claves foráneas con `on delete` y `on update`.
- Índices: único, `clustered` o `nonclustered`, columnas con orden, `include` y filtro (`where`).
- Checks.

No incluye: colación por columna, particiones y filegroups, compresión, opciones de índice (`fillfactor`, `online`…), índices columnstore, xml, spatial y en memoria, cambiar la expresión de una columna calculada existente, cambiar identity en una columna existente, propietario y permisos, reordenar columnas, ni vistas, secuencias o rutinas. Una tabla **temporal** (system-versioned) o **optimizada para memoria** no se puede modificar: `tableModel` falla con un mensaje que lo dice (la app lo muestra) en vez de devolver `null`, que solo diría «todavía no permite modificar tablas». Se anota en el inventario.

## Atomicidad

`ModifyCaps.atomic = true`: se aplica con `runAtomically` en una sola transacción y, si algo falla, no queda nada. Como en PostgreSQL, un preludio fija un tiempo de espera de bloqueo, `set lock_timeout 5000`, para no colgar el diálogo si la tabla está en uso. No hay aviso de «cada sentencia confirma».

## Lectura (`SqlServerDialect.tableModel`)

Reutiliza lo que ya lee `SqlServerDialect`: `columns()` (tipo, null, identity, default, calculada), `typeText()`, `indexes()` y `foreignKeys()`. Añade lo que falta:

- `sys.check_constraints` (nombre y expresión).
- En índices: `is_unique_constraint` (distingue clave única de índice único), `is_descending_key`, `has_filter` y `filter_definition`, y el tipo (`type`) para saber cuáles no se modelan.
- Comentarios con la propiedad extendida `MS_Description` (de la tabla y de cada columna).
- Las acciones de las foráneas vienen como `NO_ACTION`, `SET_NULL`…: se cambia `_` por espacio.

Reglas de lectura:
- La clave primaria y las únicas leen su nombre real. Un índice único que no es constraint es un índice con `unique`. Los índices que respaldan una clave no entran en `indexes`.
- `KeyModel.clustered: Boolean?` (nuevo; `null` = lo que decida el motor, y PostgreSQL y MySQL lo ignoran). Se lee y se conserva al recrear la clave, para que una clave primaria no clustered no pase a clustered. No se muestra en el diálogo.
- `IndexModel.method` es `clustered` o `nonclustered`.
- Los índices columnstore, xml, spatial y en memoria van a `TableModel.skipped` y la vista previa los nombra («Fuera de este diálogo y sin tocar: …»).
- Los defaults, los checks y las expresiones de columnas calculadas vienen de `sys` entre paréntesis (`((0))`, `([a]>(0))`). Se quita **un** par exterior al leer (`SqlServerDialect.unwrap`, que no cuenta los paréntesis dentro de textos ni de `[nombres]`) y el generador lo vuelve a poner, de modo que ida y vuelta es idéntica.
- Si la colación de una columna `char` difiere de la de la base de datos, viaja en su tipo (`nvarchar(50) collate Latin1_General_CS_AS`): `alter column` sin colación la devolvería a la de la base.
- Una columna calculada lleva `generated` (la expresión) y `generatedStored` (`is_persisted`), y no lleva default.
- `identity` se lee como `Identity()` sin opciones (semilla e incremento no se editan; se conservan solo al crear).

## Generador (`SqlServerAlter.kt`, función pura como `MySqlAlter.kt`)

`SqlServerDialect.alterTable(o, before, after)`, sobre `changes` y `ColumnMoves` de `TableChanges.kt` (ya comunes). Orden:

1. **Quitar** lo que desaparece o cambia: foráneas, checks, claves (`drop constraint`), índices (`drop index`) y defaults.
2. **Columnas**: quitar, renombrar, añadir y cambiar.
3. **Crear** lo nuevo o cambiado: claves, índices, checks y foráneas; y los defaults que toquen.
4. **Comentarios** (columnas y tabla).
5. **Renombrar la tabla** al final.

Reglas:
- **Cambiar tipo o null**: `alter table t alter column [c] tipo [not] null`. SQL Server lo rechaza si la columna está en un índice, clave, foránea, check o default; esos elementos se tratan como cambiados (se quitan antes y se recrean después), como MySQL con sus dependencias. Quitar una columna quita antes su default.
- **Defaults**: son constraints con nombre. Para quitar o cambiar uno sin tener el nombre en el modelo, el generador emite un lote que lo busca en `sys.default_constraints` y lo quita con `exec`. El nuevo se crea con `alter table t add default (expr) for [c]`.
- **Renombrar**: `exec sp_rename 'esquema.t.c', 'nuevo', 'COLUMN'`; `'esquema.t.ix' … 'INDEX'`; restricciones `'esquema.nombre' … 'OBJECT'`; tabla `'esquema.t', 'nuevo'`. Si una columna renombrada la usa un check, una columna calculada o un índice filtrado, ese elemento se quita y se vuelve a crear con el nombre nuevo (la prueba con servidor comprueba cuáles lo necesitan de verdad).
- **Comentarios**: `sp_addextendedproperty`, `sp_updateextendedproperty` o `sp_dropextendedproperty` con `MS_Description`, según haya valor antes y después.
- Los nombres usan `Dialect.quote` (corchetes, `]` duplicado) y los literales duplican las comillas simples; los esquemas de la tabla van calificados.
- **Identity en una columna existente**: SQL Server no puede añadirlo ni quitarlo. El diálogo lo deshabilita (ver abajo) y el generador falla con un mensaje claro si el modelo llega con ese cambio.
- Sin cambios devuelve lista vacía.

## Diálogo

- `supportsModifyTable` incluye `MSSQL` (`Alterations.kt`). El menú «Modificar…» ya depende de eso y de que la conexión no sea de solo lectura.
- `ModifyCaps` de SQL Server: `atomic = true`, `deferrable = false`, `identityOptions = false`, `singleIdentity = true`, `virtualColumns = true` (la casilla «Almacenada» es `persisted`), `indexMethods = clustered, nonclustered`, `fkActions = NO ACTION, CASCADE, SET NULL, SET DEFAULT` (sin `RESTRICT`).
- **Dos banderas cambian de significado** para poder describir SQL Server:
  - `indexExtras` hoy agrupa «colación y clase de operador por columna» con «`include` y condición». Se separa: `indexColumnOptions` (colación y clase de operador: PostgreSQL sí; MySQL y SQL Server no) y `indexExtras` (`include` y condición: PostgreSQL y SQL Server sí; MySQL no). MySQL pasa `indexColumnOptions = false`, sin cambio de comportamiento.
  - `singleIdentity` hoy significa «una por tabla» y «tiene que encabezar una clave» y rotula la casilla «auto_increment». Se separa: `singleIdentity` (una por tabla), `identityLeadsKey` (nuevo: solo MySQL) y `identityName` (nuevo, por defecto «Identity»; MySQL «auto_increment»). En `tableProblem` la comprobación de encabezar una clave pasa a `identityLeadsKey`.
- Nueva bandera `identityOnCreateOnly` (SQL Server): la casilla de identity queda deshabilitada en las columnas que ya existen (`col.origin != null`).
- No sale el aviso de «cada sentencia confirma» (es atómico). El de «Fuera de este diálogo y sin tocar» sigue saliendo, por `skipped`. Hoy el texto del primero está fijo en `ModifyDraft.kt` y nombra a MySQL; no hace falta tocarlo, pero conviene que la fase 4 lo deje dependiente del motor.

## Aplicar

`AppState.runModify` elige `runAtomically` o `runStepwise` por `caps.atomic`. El preludio de cada motor pasa a ser `ModifyCaps.prelude` en vez de comprobar el tipo de conexión en `AppState`: PostgreSQL `set local lock_timeout = '5s'`, MySQL y MariaDB `mySqlPrelude`, SQL Server `set lock_timeout 5000`. El mensaje de «otra transacción tiene bloqueada la tabla» también reconoce el de SQL Server («Lock request time out period exceeded»). El historial anota las sentencias como en PostgreSQL.

## Pruebas (Testcontainers, `assumeDocker()` como las demás)

- **Generador (sin Docker), `SqlServerAlterTest`:** un caso por tipo de cambio, con listas exactas de sentencias como `MySqlAlterTest`: renombrar, quitar (con su default) y añadir columna, tipo, not null, default, identity al crear, columna calculada, cada clase de restricción (añadir, quitar, recrear, solo renombrar), índices con orden, `include`, filtro y `clustered`, comentarios con comilla simple, renombrar la tabla, columna renombrada que usa un check o un índice, sin cambios.
- **Servidor, `mssql/server:2022-latest`:** `TableModelSqlServerTest` lee una tabla con todos los elementos y comprueba los campos, y una temporal falla con su motivo; `TableAlterSqlServerTest` aplica un conjunto de cambios con el generador y relee el modelo para comprobar que coincide, que ida y vuelta sin cambios no genera sentencias, y que un fallo a mitad no deja nada aplicado.
- **Reglas:** `TableModelRulesTest` cubre las banderas separadas (MySQL y PostgreSQL no cambian).
- **Interfaz:** abrir «Modificar…» en una conexión SQL Server: sin campos de diferible ni de colación, con `include` y condición en los índices, casilla de identity deshabilitada en una columna existente.

## Fases

1. Spec (este documento).
2. Leer el modelo de SQL Server, con `KeyModel.clustered` y su test de servidor.
3. Generador `SqlServerAlter.kt`, con sus tests.
4. `ModifyCaps` (banderas separadas), preludio y aplicación; test de servidor del generador.
5. Habilitar el menú; prueba de interfaz.
6. Inventario, `ui-design.md` y estado de esta spec.

Cada fase termina con sus pruebas pasando y se commitea sola.
