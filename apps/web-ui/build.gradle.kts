import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

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
            implementation(npm("@js-joda/core", "3.2.0"))
        }
    }
}
