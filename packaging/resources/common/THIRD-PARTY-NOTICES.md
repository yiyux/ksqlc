# Componentes de terceros

ksqlc se distribuye bajo GNU GPL v3 (ver `LICENSE`). Cada instalador y cada zip incluye, sin modificar, las bibliotecas
de esta lista. Sus textos de licencia están en la carpeta `licenses/` de este directorio, o dentro del propio jar
cuando este los trae (`META-INF/`). El código fuente de ksqlc es el del tag de la versión en https://github.com/yiyux/ksqlc.

> Regenerar esta lista al cambiar dependencias (`./gradlew :app:dependencies --configuration runtimeClasspath`).

## Drivers JDBC

| Driver | Versión | Licencia | Texto |
|---|---|---|---|
| SQLite (`org.xerial:sqlite-jdbc`) | 3.47.1.0 | Apache-2.0 (SQLite es de dominio público) | en el jar |
| PostgreSQL (`org.postgresql:postgresql`) | 42.7.4 | BSD-2-Clause | en el jar |
| MySQL Connector/J (`com.mysql:mysql-connector-j`) | 26.7.0 | GPL-2.0 con Universal FOSS Exception 1.0 | `licenses/MySQL-Connector-J-GPLv2-UFE.txt` |
| MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`) | 3.5.10 | LGPL-2.1 o posterior | `licenses/LGPL-2.1.txt` |
| SQL Server (`com.microsoft.sqlserver:mssql-jdbc`) | 13.6.0.jre11 | MIT | `licenses/MIT-mssql-jdbc.txt` |
| Oracle (`com.oracle.database.jdbc:ojdbc11`) | 23.6.0.24.10 | Oracle Free Use Terms and Conditions 1.0 | `licenses/Oracle-JDBC-Free-Use-Terms-1.0.txt` |
| Cassandra (`com.ing.data:cassandra-jdbc-wrapper`) y su driver DataStax | 5.0.4 / 4.19.3 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Trino (`io.trino:trino-jdbc`, jar autocontenido) | 484 | Apache-2.0 | `licenses/Apache-2.0.txt` |

Notas:

- **Todos los drivers van sin modificar**, como jars separados dentro de la aplicación. Esto lo exigen la licencia de Oracle
  (solo se puede redistribuir sin modificar) y la LGPL de MariaDB (el usuario puede sustituir el jar por otra versión).
- **Oracle**: se redistribuye bajo sus «Free Use Terms»: se incluye copia de la licencia, no se cobra por el driver,
  no se quitan los avisos de Oracle, y no se permite la ingeniería inversa ni infringir las leyes de exportación.
- **MySQL Connector/J**: es GPL-2.0 con la Universal FOSS Exception, que permite combinarlo con software libre
  (como ksqlc, GPL-3.0) distribuido con su código fuente completo. Su fuente está en https://github.com/mysql/mysql-connector-j.
- **MariaDB Connector/J**: fuente en https://github.com/mariadb-corporation/mariadb-connector-j.

## Otros componentes

- Interfaz y lenguaje: Kotlin 2.1.0 y kotlinx (coroutines, serialization, datetime, atomicfu), Compose Multiplatform 1.7.3, androidx (lifecycle, collection, annotation) y Skiko. Todo Apache-2.0.
- Fuentes Inter y JetBrains Mono: SIL Open Font License 1.1 (textos en `app/src/main/resources/fonts`).
- Entorno de ejecución de Java incluido en el paquete: GNU GPL v2 con Classpath Exception (avisos en `runtime/.../legal`).

## Bibliotecas Java (sin el interfaz, ya listado)

| Componente | Versión | Licencia |
|---|---|---|
| `at.favre.lib:hkdf` | 1.1.0 | Apache-2.0 |
| `com.datastax.astra:astra-sdk-devops` | 2.3.2 | Apache-2.0 |
| `com.datastax.oss:native-protocol` | 1.5.2 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson:jackson-bom` | 2.22.0 | Apache-2.0 |
| `com.github.ben-manes.caffeine:caffeine` | 3.3.0 | Apache-2.0 |
| `com.github.hypfvieh:dbus-java-core` | 4.2.1 | MIT |
| `com.github.hypfvieh:dbus-java-transport-native-unixsocket` | 4.2.1 | MIT |
| `com.github.javakeyring:java-keyring` | 1.0.4 | BSD-3-Clause |
| `com.github.jnr:jffi` | 1.3.9 | Apache-2.0 |
| `com.github.jnr:jnr-a64asm` | 1.0.0 | Apache-2.0 |
| `com.github.jnr:jnr-constants` | 0.10.3 | Apache-2.0 |
| `com.github.jnr:jnr-ffi` | 2.2.11 | Apache-2.0 |
| `com.github.jnr:jnr-posix` | 3.1.15 | LGPL-2.1 (triple licencia EPL-2.0 / GPL-2.0 / LGPL-2.1; se usa LGPL-2.1) |
| `com.github.jnr:jnr-x86asm` | 1.0.2 | MIT |
| `com.github.mwiede:jsch` | 2.28.8 | BSD-3-Clause e ISC |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 | Apache-2.0 |
| `com.google.protobuf:protobuf-java` | 4.31.1 | BSD-3-Clause |
| `com.ing.data:cassandra-jdbc-wrapper` | 5.0.4 | Apache-2.0 |
| `com.instaclustr:cassandra-driver-kerberos` | 3.0.0 | Apache-2.0 |
| `com.microsoft.sqlserver:mssql-jdbc` | 13.6.0.jre11 | MIT |
| `com.mysql:mysql-connector-j` | 26.7.0 | GPL-2.0 con Universal FOSS Exception 1.0 |
| `com.opencsv:opencsv` | 5.12.0 | Apache-2.0 |
| `com.oracle.database.jdbc:ojdbc11` | 23.6.0.24.10 | Oracle Free Use Terms and Conditions 1.0 (propietaria; ver nota) |
| `com.typesafe:config` | 1.4.1 | Apache-2.0 |
| `com.zaxxer:HikariCP` | 6.2.1 | Apache-2.0 |
| `commons-beanutils:commons-beanutils` | 1.11.0 | Apache-2.0 |
| `commons-codec:commons-codec` | 1.15 | Apache-2.0 |
| `commons-collections:commons-collections` | 3.2.2 | Apache-2.0 |
| `commons-io:commons-io` | 2.22.0 | Apache-2.0 |
| `commons-logging:commons-logging` | 1.3.5 | Apache-2.0 |
| `de.swiesend:secret-service` | 1.8.1-jdk17 | MIT |
| `io.dropwizard.metrics:metrics-core` | 4.1.18 | Apache-2.0 |
| `io.netty:netty-buffer` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-codec` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-common` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-handler` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-resolver` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-transport` | 4.1.130.Final | Apache-2.0 |
| `io.netty:netty-transport-native-unix-common` | 4.1.130.Final | Apache-2.0 |
| `io.trino:trino-jdbc` | 484 | Apache-2.0 (jar autocontenido: trae sus dependencias reubicadas) |
| `jakarta.annotation:jakarta.annotation-api` | 3.0.0 | EPL-2.0 o GPL-2.0 con Classpath Exception |
| `javax.validation:validation-api` | 2.0.0.Final | Apache-2.0 |
| `net.java.dev.jna:jna` | 5.13.0 | Apache-2.0 (doble licencia LGPL-2.1+ / Apache-2.0; se usa Apache-2.0) |
| `net.java.dev.jna:jna-platform` | 5.13.0 | Apache-2.0 (doble licencia LGPL-2.1+ / Apache-2.0; se usa Apache-2.0) |
| `org.apache.cassandra:java-driver-core` | 4.19.3 | Apache-2.0 |
| `org.apache.cassandra:java-driver-guava-shaded` | 4.19.3 | Apache-2.0 |
| `org.apache.commons:commons-collections4` | 4.6.0 | Apache-2.0 |
| `org.apache.commons:commons-lang3` | 3.21.0 | Apache-2.0 |
| `org.apache.commons:commons-text` | 1.13.1 | Apache-2.0 |
| `org.apache.httpcomponents.client5:httpclient5` | 5.6.4 | Apache-2.0 |
| `org.apache.httpcomponents.core5:httpcore5` | 5.4.3 | Apache-2.0 |
| `org.apache.httpcomponents.core5:httpcore5-h2` | 5.4.3 | Apache-2.0 |
| `org.checkerframework:checker-qual` | 3.42.0 | MIT |
| `org.hdrhistogram:HdrHistogram` | 2.1.12 | BSD-2-Clause (doble licencia CC0-1.0 / BSD-2-Clause; se usa BSD-2-Clause) |
| `org.jetbrains.skiko:skiko` | 0.8.18 | Apache-2.0 |
| `org.jetbrains.skiko:skiko-awt` | 0.8.18 | Apache-2.0 |
| `org.jetbrains.skiko:skiko-awt-runtime-macos-x64` | 0.8.18 | Apache-2.0 |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |
| `org.jspecify:jspecify` | 1.0.1 | Apache-2.0 |
| `org.mariadb.jdbc:mariadb-java-client` | 3.5.10 | LGPL-2.1 o posterior |
| `org.ow2.asm:asm` | 9.2 | BSD-3-Clause |
| `org.ow2.asm:asm-analysis` | 9.2 | BSD-3-Clause |
| `org.ow2.asm:asm-commons` | 9.2 | BSD-3-Clause |
| `org.ow2.asm:asm-tree` | 9.2 | BSD-3-Clause |
| `org.ow2.asm:asm-util` | 9.2 | BSD-3-Clause |
| `org.postgresql:postgresql` | 42.7.4 | BSD-2-Clause |
| `org.projectlombok:lombok` | 1.18.46 | MIT |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 |
| `org.semver4j:semver4j` | 6.0.0 | MIT |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT |
| `org.xerial:sqlite-jdbc` | 3.47.1.0 | Apache-2.0 |
| `pt.davidafsilva.apple:jkeychain` | 1.1.0 | BSD-2-Clause |
| `software.amazon.awssdk:annotations` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:apache5-client` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:auth` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:aws-core` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:aws-json-protocol` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:checksums` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:checksums-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:endpoints-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:http-auth` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:http-auth-aws` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:http-auth-aws-eventstream` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:http-auth-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:http-client-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:identity-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:json-utils` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:metrics-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:profiles` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:protocol-core` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:regions` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:retries` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:retries-spi` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:sdk-core` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:secretsmanager` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:third-party-jackson-core` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:utils` | 2.55.11 | Apache-2.0 |
| `software.amazon.awssdk:utils-lite` | 2.55.11 | Apache-2.0 |
| `software.amazon.eventstream:eventstream` | 1.0.1 | Apache-2.0 |
| `software.aws.mcs:aws-sigv4-auth-cassandra-java-driver-plugin` | 4.0.9 | Apache-2.0 |
| `tools.jackson.core:jackson-core` | 3.2.3 | Apache-2.0 |
| `tools.jackson.core:jackson-databind` | 3.2.3 | Apache-2.0 |
| `tools.jackson:jackson-bom` | 3.2.3 | Apache-2.0 |
