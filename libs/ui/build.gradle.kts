import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("mimir.android-support")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
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
// Скриншотные тесты пишут PNG и запускаются только с -Pmimir.screenshots=true (mise run screenshots).
val runScreenshots = providers.gradleProperty("mimir.screenshots").map { it.toBoolean() }.orElse(false).get()
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (!runScreenshots) {
        filter { excludeTestsMatching("*ScreenshotTest") }
    }
}

// UI-тесты :libs:ui живут только в desktopTest; wasmJs-тестов нет, поэтому проверка Compose
// (CMP-4906, требует binaries.executable() для бандла Skiko) здесь ложно срабатывает в `build`.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
