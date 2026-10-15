# DataGrip 2026.2.6 (262.10968.148) — inventario funcional

Fuente: metadatos del bundle (`product-info.json`, nombres de módulos en
`plugins/DatabaseTools/lib/modules`, ids de `<action>` en `META-INF/plugin.xml` de
`database-plugin.jar`). Solo nombres/estructura; no se decompiló ni copió código ni recursos.
Clean-room: ksqlc implementa estas capacidades con código propio.

## Dialectos (módulos `intellij.database.dialects.*`)
Núcleo ksqlc: postgres, mysql/maria, sqlite, mssql, oracle.
Futuro: h2, derby, hsql, db2, sybase, vertica, redshift, snowflake, greenplum, cockroach,
clickhouse, bigquery, cassandra, hive/spark, exasol, mongo, redis, generic/sql92.

## Áreas funcionales (por prefijo de acción) → fase ksqlc
| Área | Capacidades observadas (ids) | Fase |
|---|---|---|
| Data sources | AddDataSourceFromTemplate/Path/Url, AddDriver, Properties, ImportDataSources, MoveToGroup, CreateNewFolder, AssignColor | 1 |
| Database Explorer | Refresh/ForceRefresh, Filter, FullTextSearch, introspection levels 1-3, AddSchemas/HideSchemas, ScrollFromEditor, CopyAction, Drop | 2 |
| DDL / alteraciones | CopyDdl, OpenDdlInConsole, Ddl.AlterObject, Alterations.* (Truncate, DropPK/FK, Comment, Grants, Enable/DisableConstraints, RefreshMatView, Recompile), SqlGenerator | 2 |
| Consola SQL | Jdbc.OpenConsole.*, Execute (+Selection), Cancel, ExplainPlan/Analyse (+Raw), Debug, ToggleParameters/EditParameter, BrowseHistory, Notebook mode, Sessions (Close/Rename), DbmsOutput | 1,5 |
| Editor SQL | SelectCurrentStatement, ChangeDialect, IntroduceAlias, ExtractNamedQuery, ExtractFunction, GotoDatabaseObject | 3 |
| Grid de resultados | Edit/Submit/PreviewDml, Filter criteria, Ordering, SetNull/Default/NOW, InsertRandomUUID, LoadFullCell, SaveLobAs, ShowQuery/CopyQuery, ExportToClipboard, SelectRow/Column, MaximizeEditingCell, RenameTab, SkipGenerated/ComputedColumns | 1,4 |
| Transacciones | Transaction.Commit/Rollback, SubmitAndCommit, RevertAndRollback (modo manual/auto) | 4 |
| Import/Export | ShowDumpDialog, ImportTable, ImportFromSql, Dump/Restore.Native, CopyTable, DumpToSqlDataSource | 5 |
| Diff | ShowDiff, ShowContentDiff | 5 |
| Diagramas | ShowUmlDiagram (módulo diagrams) | descartado |
| Historial | DataSource.History.*, Console.Jdbc.BrowseHistory, ShowSQLLog | 5 |
| Rutinas | ExecuteRoutine, DebugRoutine | 5 |
| SSH | módulo `database.ssh` (túnel) | 5 |

## Estado de implementación (ksqlc)
| Área | Estado | Notas |
|---|---|---|
| Data sources | parcial | Formulario host/puerto/BD/SSL, URL manual, guardado con Keychain. Faltan carpetas, colores, plantillas, importar. |
| Consola SQL | implementado (fase 1) | Sentencia actual / selección / script, cancelación, pestañas de resultados, paginación. Parámetros: `:nombre` (y `?`, salvo en PostgreSQL donde es un operador) abren un diálogo antes de ejecutar, un campo por nombre distinto y uno por cada `?`; el valor se escribe como SQL (`5`, `'texto'`, `NULL`) y se sustituye en el texto, no se enlaza; se recuerda el último valor de cada `:nombre`; no cuentan los textos entre comillas, los comentarios ni los `::`. Explain: dos botones en la barra de la consola (sentencia actual o selección) abren una pestaña «Plan» con el plan como texto alineado. Explain: `EXPLAIN` en PostgreSQL y MySQL/MariaDB, `EXPLAIN QUERY PLAN` en SQLite, `SHOWPLAN_TEXT` en SQL Server (se desactiva siempre al terminar, incluso tras un error) y `EXPLAIN PLAN FOR` + `DBMS_XPLAN` en Oracle; ninguna ejecuta la sentencia. Explain analyze (PostgreSQL, MySQL, MariaDB) sí la ejecuta y pide confirmación si no es una lectura; en SQLite, SQL Server y Oracle no está disponible. Dialecto: el desplegable «Dialecto» de la barra de la consola (automático = el de la conexión) decide cómo se parte el script y qué es un parámetro: en Oracle un bloque PL/SQL termina en una línea `/`; en SQL Server una línea con solo `GO` separa lotes y no se envía; en PostgreSQL `?` no es un parámetro. El resaltado, el formateo y el autocompletado no dependen del dialecto, y Explain sigue usando el motor de la conexión. Falta notebook. |
| Database Explorer | implementado (fase 2) | Árbol perezoso namespace → grupo → objeto → columnas/índices/FK, refresco (manual y tras DDL), filtro, abrir datos con doble clic. Buscar tabla o vista por nombre (⌘⇧O o lupa del explorador): todas las tablas, vistas y vistas materializadas de todos los esquemas leídos, con varias palabras, ordenadas por prefijo del nombre; ↵ abre los datos y ⇧↵ la muestra en el explorador. Faltan niveles de introspección, mostrar esquemas de sistema y buscar dentro del DDL o de rutinas y secuencias. |
| DDL | implementado (fase 2) | Ver/copiar/abrir en consola. Nativo en MySQL/MariaDB (`SHOW CREATE`), SQLite (`sqlite_master`), PostgreSQL (vistas/rutinas nativas, tablas generadas). SQL Server con dialecto nativo (catálogo `sys.*`, corchetes, identity, columnas calculadas, índices con INCLUDE, acciones de claves foráneas, texto original de vistas y rutinas, secuencias; probado contra SQL Server 2022 real; driver mssql-jdbc incluido y opción «Confiar en el certificado del servidor» para certificados autofirmados). Oracle con dialecto nativo (diccionario `ALL_*`, solo esquemas de usuario, identity y columnas virtuales, restricciones e índices, DDL exacto de `DBMS_METADATA` sin almacenamiento; probado contra Oracle Free 23 real; driver ojdbc11 incluido). En Oracle los bloques PL/SQL (`begin`, `declare`, `create procedure|function|package|trigger|type`) terminan en una línea con solo `/`, como en SQL*Plus. |
| Alteraciones (Drop/Truncate/…) | pendiente | |
| Editor SQL | implementado (fase 3) | Autocompletado contextual (tablas, esquemas, columnas por alias, palabras clave), ir a la declaración (⌘B / ⌘clic → explorador, ⌘⌥B → DDL), comentar (⌘/), duplicar (⌘D), ampliar selección (⌘W), autocierre de paréntesis y comillas, sangría, formatear (⌘⌥L), errores en línea (comillas/comentarios/paréntesis sin cerrar, tabla y columna desconocidas), resaltado de usos del identificador. ⌘Z deshace comentar/duplicar/formatear. Faltan cambio de dialecto, IntroduceAlias, ExtractNamedQuery/Function. |
| Edición de datos | implementado (fase 4) | En las pestañas de tabla: editar celdas (doble clic / Enter / F2; Esc cancela; Tab pasa a la siguiente), poner NULL o valor por defecto, selector de fecha y hora en las columnas date/time/timestamp (calendario y reloj según el tipo; escribe el formato que pide la columna), añadir y borrar filas, Revertir, Ver SQL (vista previa del DML) y Enviar (parametrizado, todo o nada). Tablas sin clave primaria: solo insertar; vistas: solo lectura. Menú contextual de celda: «Editar en ventana…» (editor multilínea, con «Formatear JSON» en columnas json/jsonb), «Generar UUID» (texto y uuid), «Fecha y hora actual» (fechas) y, en columnas binarias, «Cargar desde archivo…» (hasta 64 MB) y «Guardar en archivo…». El calendario de columnas con zona horaria permite cambiar el desfase de 15 en 15 minutos; las columnas de fecha y hora son más anchas. Resultados de la consola: si la sentencia es un `select` simple de una sola tabla (sin joins, agrupaciones, DISTINCT, expresiones ni alias) y muestra toda su clave primaria, la pestaña del resultado se edita igual que una tabla (barra con añadir, borrar, revertir, ver SQL y enviar, con Tx propio); los cambios viajan por una conexión propia de la pestaña y el resultado se vuelve a leer por ella. Ejecutar otra cosa o cerrar la pestaña con cambios pide confirmación. Solo se activa con el esquema ya cargado (a partir de la segunda ejecución de una sesión nueva). |
| Transacciones | implementado (fase 4) | Auto/Manual por conexión (se guarda con ella): en Manual, consola y pestañas de tabla no confirman solas y tienen Commit/Rollback, con un punto cuando hay trabajo sin confirmar. Se pregunta antes de cerrar pestañas, desconectar, cambiar de conexión o cerrar la ventana con cambios sin enviar o transacciones abiertas. |
| Historial de consultas | hecho | Registro por conexión en `~/.ksqlc/history`, diálogo con búsqueda (⌥⌘E). |
| Exportar datos | hecho | CSV, TSV, JSON y SQL INSERT a archivo o portapapeles desde pestañas de tabla, resultados y explorador. |
| Importar CSV | hecho | Asignación de columnas, vista previa, transacción única, error con fila y columna; «Abrir archivo SQL…» en la consola. |
| Dump y restauración nativos | hecho | pg_dump/psql/pg_restore, mysqldump/mysql (y variantes MariaDB) y sqlite3, con la contraseña por entorno; SQL Server y Oracle no. |
| Apache Cassandra | implementado | Motor nuevo (driver JDBC `cassandra-jdbc-wrapper`, URL `jdbc:cassandra://host:puerto/keyspace?localdatacenter=…`; el formulario pide keyspace y datacenter local). Explorador sobre `system_schema`: keyspaces (sin los de sistema), tablas, vistas materializadas y funciones, con clave = partición + clustering e índices. DDL reconstruido (CQL no tiene `SHOW CREATE`). Edición de filas como upsert/delete por clave primaria; las colecciones, tuplas y UDT no se editan desde la rejilla. Sin transacciones (no hay Tx Auto/Manual, un lote no es atómico), sin Explain ni dump nativo. En tablas, ordenar o filtrar por columnas que no son clave exige `ALLOW FILTERING` / clustering y lo rechaza el servidor. |
| Comparar esquemas | hecho | DDL de dos esquemas (de la conexión activa o de cualquier data source guardado), emparejado por tipo y nombre, normalizado sin el nombre del esquema. |
| Diff de contenido de tablas | pendiente | fuera de la fase 5 (el diagrama ER se descartó) |

