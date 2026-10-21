import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":drivers"))
    implementation(project(":storage"))
    implementation(project(":sql"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mssqlserver:2.0.5")
    testImplementation("org.testcontainers:testcontainers-oracle-free:2.0.5")
    testImplementation("org.testcontainers:testcontainers-cassandra:2.0.5")
}
compose.desktop { application {
    mainClass = "ksqlc.app.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "ksqlc"
        packageVersion = "1.0.1"
        // los drivers JDBC (java.sql, java.naming, ...) necesitan módulos que jlink omitiría
        // shortcut: engorda el runtime ~40 MB, cambiar a modules(...) con :app:suggestRuntimeModules si importa
        includeAllModules = true
        // avisos de licencia de terceros (packaging/resources/common) dentro de cada paquete.
        // Los jars de los drivers deben ir sin modificar (lo exige la licencia de Oracle): sin ProGuard ni fat-jar.
        appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("packaging/resources"))
        macOS { iconFile.set(project.file("icons/ksqlc.icns")) }
        windows {
            iconFile.set(project.file("icons/ksqlc.ico"))
            menuGroup = "ksqlc"
            upgradeUuid = "6f1c2a52-8d3e-4b7a-9c41-3e5d0b7a1f26"
        }
        linux { iconFile.set(project.file("icons/ksqlc.png")) }
    }
} }
