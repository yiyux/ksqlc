plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    testImplementation(kotlin("test"))
}
