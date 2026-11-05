plugins {
    kotlin("multiplatform")
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    js {
        browser {
            webpackTask { mainOutputFileName = "main.js" }
        }
        binaries.executable()
        compilerOptions { target = "es2015" } // class MimirPlugin : Plugin() должен быть настоящим ES-классом
    }
    sourceSets {
        jsMain.dependencies { implementation(project(":libs:core")) }
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
