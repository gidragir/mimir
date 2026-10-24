import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

dependencies {
    implementation(project(":core"))
    implementation(project(":drive"))
    implementation(project(":daemon"))
    implementation(project(":ui"))
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native)
    implementation(compose.desktop.currentOs)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "dev.mimir.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = providers.gradleProperty("mimir.appName").get()
            packageVersion = providers.gradleProperty("mimir.version").get()
            windows {
                perUserInstall = true
                upgradeUuid = providers.gradleProperty("mimir.msiUpgradeUuid").get()
            }
        }
    }
}

// Wayland-композиторы без репарентинга (niri): иначе AWT рисует окно неверно
// задача run регистрируется Compose-плагином позже, поэтому configureEach
tasks.withType<JavaExec>().configureEach {
    if (name == "run") environment("_JAVA_AWT_WM_NONREPARENTING", "1")
}

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }
