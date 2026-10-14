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
}
compose.desktop { application {
    mainClass = "ksqlc.app.MainKt"
    nativeDistributions { targetFormats(TargetFormat.Dmg); packageName = "ksqlc" }
} }
