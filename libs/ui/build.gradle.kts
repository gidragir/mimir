import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("mimir.android-support")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable() // нужен проверке Compose UI tests (CMP-4906); входная точка живёт в :web-ui
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":libs:core"))
            api(project(":libs:client"))
            implementation(project(":libs:drive"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
        }
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.compose.ui.test)
                implementation(libs.compose.ui.test.junit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

dependencies {
    if (dev.mimir.build.AndroidSdk.isAvailable(rootDir)) {
        add("androidRuntimeClasspath", libs.compose.ui.tooling)
    }
}

// Skiko загружает нативную библиотеку через System.load.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
