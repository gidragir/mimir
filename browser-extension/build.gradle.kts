plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.buildconfig)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

fun googleSecret(name: String) = providers.environmentVariable("MIMIR_BUILD_GOOGLE_$name")
    .orElse(providers.environmentVariable("MIMIR_GOOGLE_$name"))
    .orElse("")

buildConfig {
    packageName("dev.mimir.extension")
    className("ExtensionBuildConfig")
    buildConfigField("GOOGLE_DESKTOP_CLIENT_ID", googleSecret("DESKTOP_CLIENT_ID"))
    buildConfigField("GOOGLE_DESKTOP_CLIENT_SECRET", googleSecret("DESKTOP_CLIENT_SECRET"))
    buildConfigField("GOOGLE_DEVICE_CLIENT_ID", googleSecret("DEVICE_CLIENT_ID"))
    buildConfigField("GOOGLE_DEVICE_CLIENT_SECRET", googleSecret("DEVICE_CLIENT_SECRET"))
}

kotlin {
    js {
        browser {
            webpackTask { mainOutputFileName = "popup.js" }
            testTask {
                useKarma { useChromeHeadless() }
                if (System.getenv("CHROME_BIN") == null && file("/usr/bin/chromium").exists()) {
                    environment("CHROME_BIN", "/usr/bin/chromium")
                }
            }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":core"))
            implementation(project(":drive"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
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
        "extensionPublicKey" to providers.gradleProperty("mimir.extensionPublicKey").get(),
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
    from("src/jsMain/resources") { include("popup.css", "background.js") }
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
