// Плагины объявлены здесь с apply false, чтобы все модули делили один classloader.
plugins {
    id("com.autonomousapps.dependency-analysis")
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.buildconfig) apply false
}

// DAGP 3.20.1 падает на KMP-проектах с js/wasm/android-таргетами (Graphs.lowestCommonAncestor:
// "Empty collection can't be reduced"), поэтому анализ включён только для JVM- и Android-модулей.
subprojects {
    listOf("org.jetbrains.kotlin.jvm", "com.android.application").forEach { id ->
        pluginManager.withPlugin(id) {
            apply(plugin = "com.autonomousapps.dependency-analysis")
        }
    }
}

dependencyAnalysis {
    structure {
        ignoreKtx(true)
    }
    issues {
        all {
            onAny {
                severity("warn")
            }
        }
    }
}

// Быстрый агрегатор всех JVM-тестов (включая build-logic) без сборки Wasm/JS/Webpack/Android APK.
tasks.register("ciJvmTests") {
    val aggregator = this
    group = "verification"
    description = "Прогнать все JVM-тесты всех модулей и build-logic без Wasm/JS-бандлов"
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
    subprojects.filter { it.name != "android" }.forEach { sub ->
        sub.tasks.matching { it.name in setOf("test", "jvmTest", "desktopTest") }.all {
            val jvmTestTask = this
            aggregator.dependsOn(jvmTestTask)
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

// Исключаем параллельный старт двух процессов Yarn на Windows (--mutex network race).
tasks.matching { it.name == "kotlinWasmNpmInstall" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "kotlinWasmToolingSetup" })
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        lockFileDirectoryProperty.set(layout.projectDirectory.dir("gradle/yarn"))
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().apply {
        lockFileDirectoryProperty.set(layout.projectDirectory.dir("gradle/yarn/wasm"))
    }
}

