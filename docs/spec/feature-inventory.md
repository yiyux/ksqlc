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
| Diagramas | ShowUmlDiagram (módulo diagrams) | 5 |
| Historial | DataSource.History.*, Console.Jdbc.BrowseHistory, ShowSQLLog | 5 |
| Rutinas | ExecuteRoutine, DebugRoutine | 5 |
| SSH | módulo `database.ssh` (túnel) | 5 |

## Estado de implementación (ksqlc)
| Área | Estado | Notas |
|---|---|---|
| Data sources | parcial | Formulario host/puerto/BD/SSL, URL manual, guardado con Keychain. Faltan carpetas, colores, plantillas, importar. |
| Consola SQL | implementado (fase 1) | Sentencia actual / selección / script, cancelación, pestañas de resultados, paginación. Faltan parámetros, notebook, explain. |
| Database Explorer | implementado (fase 2) | Árbol perezoso namespace → grupo → objeto → columnas/índices/FK, refresco (manual y tras DDL), filtro, abrir datos con doble clic. Faltan niveles de introspección, mostrar esquemas de sistema, búsqueda de texto completo. |
| DDL | implementado (fase 2) | Ver/copiar/abrir en consola. Nativo en MySQL/MariaDB (`SHOW CREATE`), SQLite (`sqlite_master`), PostgreSQL (vistas/rutinas nativas, tablas generadas). SQL Server con dialecto nativo (catálogo `sys.*`, corchetes, identity, columnas calculadas, índices con INCLUDE, acciones de claves foráneas, texto original de vistas y rutinas, secuencias; probado contra SQL Server 2022 real; driver mssql-jdbc incluido y opción «Confiar en el certificado del servidor» para certificados autofirmados). Oracle con dialecto nativo (diccionario `ALL_*`, solo esquemas de usuario, identity y columnas virtuales, restricciones e índices, DDL exacto de `DBMS_METADATA` sin almacenamiento; probado contra Oracle Free 23 real; driver ojdbc11 incluido). En Oracle los bloques PL/SQL (`begin`, `declare`, `create procedure|function|package|trigger|type`) terminan en una línea con solo `/`, como en SQL*Plus. |
| Alteraciones (Drop/Truncate/…) | pendiente | |
| Editor SQL | implementado (fase 3) | Autocompletado contextual (tablas, esquemas, columnas por alias, palabras clave), ir a la declaración (⌘B / ⌘clic → explorador, ⌘⌥B → DDL), comentar (⌘/), duplicar (⌘D), ampliar selección (⌘W), autocierre de paréntesis y comillas, sangría, formatear (⌘⌥L), errores en línea (comillas/comentarios/paréntesis sin cerrar, tabla y columna desconocidas), resaltado de usos del identificador. ⌘Z deshace comentar/duplicar/formatear. Faltan cambio de dialecto, IntroduceAlias, ExtractNamedQuery/Function. |
| Edición de datos | implementado (fase 4) | En las pestañas de tabla: editar celdas (doble clic / Enter / F2; Esc cancela; Tab pasa a la siguiente), poner NULL o valor por defecto, selector de fecha y hora en las columnas date/time/timestamp (calendario y reloj según el tipo; escribe el formato que pide la columna), añadir y borrar filas, Revertir, Ver SQL (vista previa del DML) y Enviar (parametrizado, todo o nada). Tablas sin clave primaria: solo insertar; vistas: solo lectura. Falta editar resultados de la consola, editores especiales (LOB, JSON) y generar valores (UUID/NOW). |
| Transacciones | implementado (fase 4) | Auto/Manual por conexión (se guarda con ella): en Manual, consola y pestañas de tabla no confirman solas y tienen Commit/Rollback, con un punto cuando hay trabajo sin confirmar. Se pregunta antes de cerrar pestañas, desconectar, cambiar de conexión o cerrar la ventana con cambios sin enviar o transacciones abiertas. |
| Historial de consultas | hecho | Registro por conexión en `~/.ksqlc/history`, diálogo con búsqueda (⌥⌘E). |
| Exportar datos | hecho | CSV, TSV, JSON y SQL INSERT a archivo o portapapeles desde pestañas de tabla, resultados y explorador. |
| Importar, dump, diff, ER | pendiente | fase 5 |

