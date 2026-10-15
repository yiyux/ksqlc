plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    implementation(project(":sql"))
    implementation("com.zaxxer:HikariCP:6.2.1")
    runtimeOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("com.mysql:mysql-connector-j:26.7.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:13.6.0.jre11")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10")
    runtimeOnly("com.ing.data:cassandra-jdbc-wrapper:5.0.4")
    implementation("com.github.mwiede:jsch:2.28.8")
    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mssqlserver:2.0.5")
    testImplementation("org.testcontainers:testcontainers-oracle-free:2.0.5")
    testImplementation("org.testcontainers:testcontainers-cassandra:2.0.5")

}
