import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":libs:ui"))
            implementation(project(":libs:core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(npm("@js-joda/core", libs.versions.jsJodaCore.get()))
        }
    }
}
