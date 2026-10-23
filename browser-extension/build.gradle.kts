plugins {
    kotlin("multiplatform")
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

kotlin {
    js {
        browser {
            webpackTask { mainOutputFileName = "popup.js" }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies { implementation(project(":core")) }
    }
}

val extensionDir = layout.buildDirectory.dir("extension")

val assembleExtension by tasks.registering(Sync::class) {
    group = "build"
    description = "Собрать расширение Chromium (MV3)"
    dependsOn("jsBrowserProductionWebpack")
    into(extensionDir)
    val props = mapOf(
        "appName" to providers.gradleProperty("mimir.appName").get(),
        "version" to providers.gradleProperty("mimir.version").get(),
    )
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("popup.js") }
    from("manifest.template.json") {
        rename { "manifest.json" }
        expand(props)
    }
    from("src/jsMain/resources") {
        include("popup.html")
        expand(props)
    }
    from("src/jsMain/resources") { include("icon-128.png") }
}

val zipExtension by tasks.registering(Zip::class) {
    group = "build"
    description = "Упаковать расширение в zip для релиза"
    from(assembleExtension)
    archiveFileName = "${providers.gradleProperty("mimir.pacmanName").get()}-extension-${providers.gradleProperty("mimir.version").get()}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

val checkExtension by tasks.registering(Exec::class) {
    group = "verification"
    description = "Проверить манифест MV3 и popup"
    dependsOn(assembleExtension)
    commandLine("node", "scripts/check-extension.mjs", extensionDir.get().asFile.path)
}

tasks.named("check") { dependsOn(checkExtension) }
