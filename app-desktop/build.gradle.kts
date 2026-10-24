import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

dependencies {
    implementation(project(":core"))
    implementation(project(":daemon"))
    implementation(project(":ui"))
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native)
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
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

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }
