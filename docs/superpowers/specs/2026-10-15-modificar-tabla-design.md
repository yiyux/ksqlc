# Modificar una tabla (diálogo «Modify»)

Fecha: 2026-10-15 · Estado: implementado

## Objetivo

Desde el explorador, «Modificar…» sobre una tabla abre un diálogo como el «Modify» de DataGrip: un árbol de la tabla (columnas, claves, claves foráneas, índices, checks), un formulario por cada nodo y una vista previa del SQL que se ejecutará. OK aplica los cambios y Cancelar los descarta.

Éxito: un usuario de PostgreSQL puede cambiar nombre, comentario, columnas, claves, claves foráneas, índices y checks de una tabla viendo el SQL exacto antes de ejecutarlo, y el resultado queda aplicado todo o nada.

## Alcance

**Primera versión: solo PostgreSQL, solo tablas.** El diálogo y el modelo no dependen del motor; lo que depende de él (leer el modelo y generar el SQL) va detrás de dos métodos de `Dialect`, así que los demás motores se añaden después uno a uno.

Incluye:
- Tabla: nombre y comentario.
- Columnas: añadir, quitar, renombrar, tipo, not null, default, comentario, identity (always / by default, con mínimo, máximo, paso, caché y cíclica). Una columna generada (`generated always as (…) stored`) solo se puede definir al crearla.
- Claves: primaria y únicas (nombre, columnas, diferible, inicialmente diferida).
- Claves foráneas: nombre, columnas, tabla y columnas referidas, `on delete`, `on update`, diferible.
- Índices (los que no respaldan una clave): nombre, único, método de acceso, columnas con orden, colación y clase de operador, condición (parcial) y columnas `include`.
- Checks: nombre y expresión.

No incluye (se anota en el inventario): persistencia, `with oids`, partición, opciones de almacenamiento, tablespace, propietario, permisos, restricciones de exclusión, «virtual columns» y «virtual foreign keys» de DataGrip, reordenar columnas (PostgreSQL no puede) y cambiar la expresión de una columna generada ya existente. Tampoco vistas, secuencias ni rutinas.

## Modelo (`core`, `TableModel.kt`)

Datos inmutables. Cada elemento lleva `origin`: su nombre original, o `null` si es nuevo. Así el generador distingue renombrar de quitar y crear.

```
TableModel(name, comment, columns, keys, foreignKeys, indexes, checks)
ColumnModel(origin, name, type, notNull, default, comment, identity: Identity?, generated: String?)
Identity(always, min, max, step, cache, cycle)
KeyModel(origin, name, primary, columns, deferrable, initiallyDeferred)
ForeignKeyModel(origin, name, columns, refNs, refTable, refColumns, onDelete, onUpdate, deferrable, initiallyDeferred)
IndexModel(origin, name, unique, method, columns: List<IndexColumn>, include, condition)
IndexColumn(name, order, collation, opClass)
CheckModel(origin, name, expression)
```

`type` y `default` son texto tal como los da PostgreSQL (`format_type`, `pg_get_expr`); el usuario los edita como texto.

## Lectura

`Dialect.tableModel(conn, o): TableModel?`, con `null` por omisión. PostgreSQL lo implementa con consultas a `pg_catalog` (`pg_attribute`, `pg_constraint`, `pg_index`, `pg_class`, `pg_description`, `pg_get_constraintdef`, `pg_get_indexdef`). Los índices que respaldan una clave (`pg_constraint.conindid`) no entran en `indexes`: se editan como clave. `details` y `ddl` no cambian, así que el explorador y «Ver DDL» no se ven afectados.

## Generador

`Dialect.alterTable(o, before, after): List<String>`; por omisión falla con «este motor no puede modificar tablas». En PostgreSQL es una función pura (sin conexión) en `PostgresAlter.kt`. Orden de las sentencias:

1. **Quitar** lo que desaparece o cambia: FK, checks, claves y luego índices (`alter table … drop constraint`, `drop index`).
2. **Columnas**, en este orden: quitar, renombrar, añadir y cambiar (tipo con `using "c"::tipo`, `set/drop not null`, `set/drop default`, identity, comentario).
3. **Crear** lo nuevo o cambiado: claves, checks, índices, FK.
4. **Comentarios** de tabla y columnas nuevas.
5. **Renombrar la tabla** al final, de modo que todo lo anterior use el nombre original.

Reglas:
- Una clave, FK, check o índice cuyo contenido cambia se quita y se recrea; si solo cambia el nombre, se renombra (`rename constraint`, `alter index … rename to`).
- Identity: añadir = `add generated {always|by default} as identity (opciones)`; quitar = `drop identity`; cambiar el modo = `set generated`; cambiar opciones = `set increment by`, `set minvalue`, `set maxvalue`, `set cache`, `set [no] cycle`.
- Los nombres se entrecomillan con `Dialect.quote`; los literales de comentario duplican las comillas simples.
- Sin cambios devuelve lista vacía.

## Diálogo (`app`, `ModifyDialog.kt`)

`ModalOverlay` grande, como el de Data Sources.
- **Izquierda:** árbol con la tabla y los grupos `columns`, `keys`, `foreign keys`, `indexes`, `checks` (los dos «virtual» de DataGrip no se muestran), con barra de añadir, quitar y duplicar. No hay botones de mover en el árbol (PostgreSQL no reordena columnas); subir y bajar solo existe en la lista de columnas de una clave o un índice, donde el orden sí cuenta. Reutiliza `TreeLine`.
- **Derecha:** el formulario del nodo elegido, con los campos de «Alcance». Los campos reutilizan `CompactTextField`, `DropdownField` y casillas del diálogo de Data Sources.
- **Abajo:** vista previa en monoespaciada con el SQL de `alterTable` (se recalcula con cada cambio); si el modelo no es válido (nombre vacío, clave sin columnas, check sin expresión) muestra el motivo en lugar del SQL y OK queda deshabilitado.
- **OK** con cambios que pierden datos (quitar columna, cambiar tipo) pide antes una confirmación que los nombra (reutiliza `Confirmation`).
- Menú contextual de una tabla: «Modificar…», solo si el motor lo soporta (`supportsModifyTable(kind)`, por ahora PostgreSQL) y la conexión no es de solo lectura.

## Aplicar

`AppState.applyTableChanges(statements)`: una `Session` propia con autocommit desactivado ejecuta las sentencias en orden; si una falla, `rollback()` y el error se muestra en el diálogo, que sigue abierto; si todas salen bien, `commit()`. Después se anotan en el historial, se cierra el diálogo y se refresca el explorador. Si el modelo se lee en el momento de abrir, aplicar no comprueba cambios concurrentes de otros usuarios (el servidor rechazará lo que ya no encaje).

## Pruebas

- **Generador (sin Docker):** casos por tipo de cambio: renombrar columna, quitar y añadir, tipo con `using`, not null y default, identity (añadir, quitar, opciones), cada clase de restricción (añadir, quitar, recrear, solo renombrar), índices con condición e `include`, renombrar tabla, comentarios con comilla simple, sin cambios.
- **PostgreSQL real:** leer el modelo de una tabla con todos los elementos y comprobar los campos; aplicar un conjunto de cambios con el generador y releer el modelo para comprobar que coincide con el esperado; un fallo a mitad no deja nada aplicado.
- **Interfaz:** abrir «Modificar…», renombrar una columna y añadir otra, comprobar la vista previa y aplicar con OK; el explorador muestra el cambio. Con una conexión de solo lectura la entrada no aparece.

## Fases de construcción

1. Modelo y lectura de PostgreSQL.
2. Generador.
3. Diálogo (árbol, formularios y vista previa).
4. Aplicar y conexión con el menú; actualizar el inventario.

Cada fase termina con sus pruebas pasando y puede commitearse sola.
