import java.time.Duration
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("mimir.android-support")
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlin.ExperimentalUnsignedTypes",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
        }
    }
    // Библиотеки не трогают DOM: JS-тесты идут в Node.js без Karma и Chromium.
    js {
        nodejs {
            testTask {
                timeout.set(Duration.ofMinutes(1))
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    // Тесты wasm-библиотек идут в node: Karma+Chromium падает на import.meta (klib один для любого wasm-окружения).
    // KotlinWasmNode не использует mocha (KGP игнорирует useMocha); задаём таймаут на уровне задачи Gradle.
    wasmJs {
        nodejs {
            testTask {
                timeout.set(Duration.ofMinutes(2))
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
