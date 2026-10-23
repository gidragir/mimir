import java.time.Duration
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("mimir.android-support")
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    jvm()
    js {
        browser {
            testTask { useKarma { useChromeHeadless() } }
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
