# Soporte para Trino — diseño

## Intención
Poder **consultar y explorar** Trino desde ksqlc: ejecutar SQL, navegar catálogos → esquemas → tablas/vistas, ver columnas y DDL. Pedido por el usuario; no se pidió edición de esquema, copia ni comparación de datos.

## Objetivos
- Un data source Trino igual que los demás motores (formulario, prueba de conexión, explorador, consola, Explain).
- Seguir el patrón con el que se añadió Cassandra (commit `630ad75`): un `DbKind`, un `Dialect` y ramas en los `when` existentes.

## No objetivos
Rutinas, Modificar tabla, copia/diff de tablas, dump nativo, edición de filas existentes, opciones SSL avanzadas (CA/keystore), modo de una sola base de datos, zona horaria de sesión.

## Decisiones
| Tema | Decisión |
|---|---|
| Driver | `io.trino:trino-jdbc:484` (jar autocontenido, Apache-2.0), `runtimeOnly` en `drivers` |
| URL | `jdbc:trino://host:puerto[/catálogo[/esquema]][?SSL=true]`; puerto por defecto 8080. El campo del formulario es «Catálogo/esquema» |
| Transacciones | Ninguna: `supportsTransactions(TRINO) = false`; `Session` fuerza auto-commit y la interfaz oculta los controles Tx |
| Namespace | `Namespace(catalog, schema)`; `Namespace.label` es `catálogo.esquema` cuando hay ambos (el nombre del esquema se repite entre catálogos) |
| Explorador | Todos los catálogos con `metaData.catalogs` + `getSchemas`; se ocultan `system` e `information_schema` salvo «incluir sistema» |
| Detalles de tabla | Solo columnas (Trino no expone PK, índices ni FK por JDBC); la rejilla queda «sin clave primaria» y no edita filas existentes |
| DDL | `SHOW CREATE TABLE | VIEW | MATERIALIZED VIEW` |
| Explain | `explain` / `explain analyze` |
| SSL | «Desactivado» o «Cifrado» (`SSL=true`, almacén de confianza de Java) |

## Restricciones conocidas
- Trino exige usuario y solo acepta contraseña por HTTPS; el error del driver llega tal cual a la prueba de conexión.
- Copia de tabla rechazada para Trino (sin transacciones, igual que Cassandra).

## Archivos
`core/.../Model.kt`, `core/.../Schema.kt`, `drivers/.../dialect/TrinoDialect.kt` (nuevo), `Dialect.kt`, `JdbcUrl.kt`, `Explain.kt`, `Session.kt`, `ConnectionOptionsSupport.kt`, `Alterations.kt`, `CopyTable.kt`; `app/.../ConnectionForm.kt`, `AppState.kt`, `Dialogs.kt`; inventario en `docs/spec/feature-inventory.md`; avisos en `packaging/resources/common/THIRD-PARTY-NOTICES.md`.

## Verificación
`./gradlew test` (con Docker corre `TrinoServerTest` contra `trinodb/trino:484`: namespaces, columnas, `SHOW CREATE`, consulta). Sin comprobar: el aspecto del formulario y del explorador en la interfaz.
