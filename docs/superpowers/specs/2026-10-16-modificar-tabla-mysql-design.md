# Modificar una tabla en MySQL y MariaDB

Fecha: 2026-10-16 · Estado: pendiente de revisión

## Objetivo

«Modificar…» sobre una tabla de una conexión MySQL o MariaDB abre el mismo diálogo que en PostgreSQL (ver `2026-10-15-modificar-tabla-design.md`), con la vista previa del SQL exacto antes de aplicar. El modelo (`TableModel`) y el diálogo no cambian de motor a motor; lo nuevo es leer el modelo, generar el SQL y decidir qué campos se muestran.

## Alcance

Solo **MySQL 8.0.19+ y MariaDB 10.5.2+** (las pruebas usan `mysql:8.4` y `mariadb:11.4`). Con ellas existen `rename column`, `rename index`, `drop check` y `drop constraint`, así que no hay ramas para versiones anteriores.

Incluye:
- Tabla: nombre y comentario.
- Columnas: añadir, quitar, renombrar, tipo, not null, default, comentario, `auto_increment` y columna generada (stored o virtual, solo al crearla).
- Clave primaria y claves únicas.
- Claves foráneas con `on delete` y `on update`.
- Índices: único, método (`btree` o `hash`), columnas con orden y expresiones.
- Checks.

No incluye: longitudes de prefijo (`col(10)`), índices fulltext y spatial, charset y collation, engine, particiones, propietario y permisos, reordenar columnas, columnas invisibles, ni cambiar la expresión de una columna generada ya existente. Tampoco vistas, secuencias ni rutinas. Se anota en el inventario.

## Atomicidad

En MySQL y MariaDB el DDL confirma solo, así que no hay «todo o nada». Se aplica sentencia a sentencia en orden; si una falla se **para** y el diálogo muestra cuál falló, cuáles ya quedaron aplicadas, y recarga el modelo desde el servidor para poder seguir. La vista previa avisa: «MySQL confirma cada sentencia: si una falla, las anteriores quedan aplicadas».

## Lectura (`MySqlDialect.tableModel`)

Con `information_schema`: `tables` (comentario), `columns` (tipo en `column_type`, `is_nullable`, `column_default`, `extra` para `auto_increment` y `generation_expression`, `column_comment`), `statistics` (índices y claves únicas, orden, método), `table_constraints` + `key_column_usage` + `referential_constraints` (primaria, únicas y foráneas) y `check_constraints` (con `table_constraints` para el nombre de la tabla).

- `type` es `column_type` (por ejemplo `varchar(20)`, `int unsigned`).
- La clave primaria se llama `PRIMARY` en el servidor; el modelo la lee con ese nombre.
- Los índices que respaldan una clave única o primaria no entran en `indexes`; los que respaldan una clave foránea sí (son índices normales).
- Los índices fulltext y spatial, y las partes con prefijo, no se modelan: un índice que los tenga se omite del modelo para no recrearlo mal.
- `auto_increment` se lee como `Identity()` sin opciones. Las columnas generadas llevan `generated` y no llevan default.
- El `default` se normaliza: MariaDB devuelve los literales entre comillas y MySQL 8 los devuelve sin comillas.

## Generador (`MySqlAlter.kt`, función pura como `PostgresAlter.kt`)

`MySqlDialect.alterTable(o, before, after)`. Orden:

1. **Quitar** lo que desaparece o cambia: foráneas (`drop foreign key`), checks (`drop check`), claves (`drop primary key` o `drop index`) e índices (`drop index`).
2. **Columnas**: quitar, renombrar (`rename column a to b`), añadir y cambiar. Cambiar usa `modify column` con la definición completa (tipo, null, default, comentario, `auto_increment`); no hay `using`.
3. **Crear** lo nuevo o cambiado: claves, índices, checks y foráneas.
4. **Comentario de la tabla** (`comment = '…'`).
5. **Renombrar la tabla** al final (`rename to`).

Reglas:
- Una clave, índice, check o foránea cuyo contenido cambia se quita y se recrea. Un cambio solo de nombre es `rename index` en índices y claves únicas, y quitar y recrear en foráneas y checks. La clave primaria no tiene nombre: renombrarla no genera nada.
- Reutiliza el emparejado por `origin` y el seguimiento de columnas renombradas de `PostgresAlter.kt`. Esa lógica (`changes`, `mapped`) pasa a un archivo común en vez de copiarse.
- Los nombres se entrecomillan con `Dialect.quote` (comillas inversas) y los literales duplican las comillas simples.
- `auto_increment` solo se acepta en una columna que sea la primera de una clave; si no, el modelo no es válido (lo comprueba `tableProblem`).
- Sin cambios devuelve lista vacía.

## Diálogo

- `supportsModifyTable` incluye MySQL y MariaDB (`Alterations.kt`). El menú «Modificar…» ya depende de eso y de que la conexión no sea de solo lectura.
- Una capacidad por dialecto, `ModifyCaps`, indica qué campos existen. En MySQL se ocultan: diferible e inicialmente diferida, clase de operador y colación por columna de índice, «Incluir columnas», «Condición», y las opciones de identity (queda solo la casilla `auto_increment`). El método ofrece `btree` y `hash`.
- El aviso de atomicidad sale en la vista previa cuando la capacidad `atomic` es falsa.

## Aplicar

`AppState.applyTableChanges` hoy hace rollback si algo falla. Para un motor sin DDL transaccional ejecuta con autocommit, para en el primer error y devuelve cuántas sentencias se aplicaron; el diálogo muestra el error, las aplicadas, y recarga el modelo. El historial anota las que se ejecutaron.

## Pruebas (Testcontainers, `assumeDocker()` como las demás)

- **Generador (sin Docker):** un caso por tipo de cambio, como en PostgreSQL: renombrar, quitar y añadir columna, tipo, not null y default, `auto_increment`, cada clase de restricción (añadir, quitar, recrear, solo renombrar), índices con orden y expresión, comentarios con comilla simple, renombrar la tabla, sin cambios.
- **Servidor, `mysql:8.4` y `mariadb:11.4`:** `TableModelMySqlServerTest` lee una tabla con todos los elementos y comprueba los campos; `TableAlterMySqlServerTest` aplica un conjunto de cambios con el generador y relee el modelo para comprobar que coincide con el esperado, y comprueba que un fallo a mitad deja aplicadas las anteriores y para. Se parametriza por motor para no duplicar.
- **Interfaz:** abrir «Modificar…» en una conexión MySQL, comprobar que los campos de PostgreSQL no aparecen y que sale el aviso de atomicidad.

## Fases

1. Modelo y lectura de MySQL/MariaDB, con su test de servidor.
2. Generador común extraído y generador de MySQL, con sus tests.
3. `ModifyCaps`, aviso y aplicación sin transacción en el diálogo.
4. Habilitar el menú y actualizar el inventario y `ui-design.md`.

Cada fase termina con sus pruebas pasando y se commitea sola.
