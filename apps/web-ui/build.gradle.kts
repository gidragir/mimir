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
            implementation(project(":libs:client"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(npm("@js-joda/core", libs.versions.jsJodaCore.get()))
        }
    }
}
