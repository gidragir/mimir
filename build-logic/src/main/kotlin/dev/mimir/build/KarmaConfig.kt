package dev.mimir.build

import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBrowserDsl

private const val SYSTEM_CHROMIUM = "/usr/bin/chromium"

/** Приоритет: `MIMIR_BUILD_GOOGLE_<name>` → `MIMIR_GOOGLE_<name>` → пустая строка. */
internal fun resolveGoogleSecret(name: String, env: Map<String, String>): String =
    env["MIMIR_BUILD_GOOGLE_$name"] ?: env["MIMIR_GOOGLE_$name"] ?: ""

/** Тот же приоритет, что в [resolveGoogleSecret], но через Provider API (учитывается configuration cache). */
fun Project.googleSecret(name: String): Provider<String> =
    providers.environmentVariable("MIMIR_BUILD_GOOGLE_$name")
        .orElse(providers.environmentVariable("MIMIR_GOOGLE_$name"))
        .orElse("")

/** Karma + headless Chromium с общим каталогом конфигов `gradle/karma.config.d` (DOM-зависимые JS-модули). */
fun KotlinJsBrowserDsl.configureKarmaHeadless(project: Project) {
    testTask {
        useKarma {
            useChromeHeadless()
            useConfigDirectory(project.rootProject.file("gradle/karma.config.d"))
        }
        if (System.getenv("CHROME_BIN") == null && File(SYSTEM_CHROMIUM).exists()) {
            environment("CHROME_BIN", SYSTEM_CHROMIUM)
        }
    }
}
