import dev.mimir.build.configureKarmaHeadless
import dev.mimir.build.googleSecret

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.buildconfig)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

buildConfig {
    packageName("dev.mimir.obsidian")
    className("PluginBuildConfig")
    buildConfigField("PLUGIN_ID", providers.gradleProperty("mimir.obsidianPluginId"))
    buildConfigField("VERSION", providers.gradleProperty("mimir.version"))
    buildConfigField("DAEMON_PORT", providers.gradleProperty("mimir.apiDefaultPort").map { it.toInt() })
    buildConfigField("GOOGLE_DEVICE_CLIENT_ID", project.googleSecret("DEVICE_CLIENT_ID"))
    buildConfigField("GOOGLE_DEVICE_CLIENT_SECRET", project.googleSecret("DEVICE_CLIENT_SECRET"))
}

kotlin {
    js {
        browser {
            webpackTask { mainOutputFileName = "main.js" }
            configureKarmaHeadless(project)
        }
        binaries.executable()
        compilerOptions { target = "es2015" } // class MimirPlugin : Plugin() должен быть настоящим ES-классом
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":libs:core"))
            implementation(project(":libs:drive"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
        }
        jsTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

val pluginDir = layout.buildDirectory.dir("plugin")

val assemblePlugin = tasks.register<Sync>("assemblePlugin") {
    group = "build"
    description = "Собрать плагин Obsidian: main.js + manifest.json"
    dependsOn("jsBrowserProductionWebpack")
    into(pluginDir)
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
        include("main.js")
    }
    from("manifest.template.json") {
        rename { "manifest.json" }
        expand(
            "pluginId" to providers.gradleProperty("mimir.obsidianPluginId").get(),
            "appName" to providers.gradleProperty("mimir.appName").get(),
            "version" to providers.gradleProperty("mimir.version").get(),
        )
    }
}

val zipPlugin = tasks.register<Zip>("zipPlugin") {
    group = "build"
    description = "Упаковать плагин в zip для релиза"
    from(assemblePlugin)
    archiveFileName = "${providers.gradleProperty("mimir.obsidianPluginId").get()}-${providers.gradleProperty("mimir.version").get()}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

val checkPlugin = tasks.register<Exec>("checkPlugin") {
    group = "verification"
    description = "Проверить плагин на фейковом obsidian"
    dependsOn(assemblePlugin)
    commandLine("node", "scripts/check-plugin.mjs", pluginDir.get().asFile.path)
}

tasks.named("check") { dependsOn(checkPlugin) }
