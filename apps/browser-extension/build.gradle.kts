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
    packageName("dev.mimir.extension")
    className("ExtensionBuildConfig")
    buildConfigField("ROOT_FOLDER", providers.environmentVariable("MIMIR_ROOT_FOLDER").orElse("MimirVault"))
    buildConfigField("GOOGLE_DESKTOP_CLIENT_ID", project.googleSecret("DESKTOP_CLIENT_ID"))
    buildConfigField("GOOGLE_DESKTOP_CLIENT_SECRET", project.googleSecret("DESKTOP_CLIENT_SECRET"))
    buildConfigField("GOOGLE_DEVICE_CLIENT_ID", project.googleSecret("DEVICE_CLIENT_ID"))
    buildConfigField("GOOGLE_DEVICE_CLIENT_SECRET", project.googleSecret("DEVICE_CLIENT_SECRET"))
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    js {
        browser {
            webpackTask { mainOutputFileName = "popup.js" }
            configureKarmaHeadless(project)
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":libs:core"))
            implementation(project(":libs:drive"))
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
            implementation(libs.ktor.client.mock)
        }
    }
}

val extensionDir = layout.buildDirectory.dir("extension")

val assembleExtension = tasks.register<Sync>("assembleExtension") {
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
    from("src/jsMain/resources") { include("icon-*.png") }
}

val zipExtension = tasks.register<Zip>("zipExtension") {
    group = "build"
    description = "Упаковать расширение в zip для релиза"
    from(assembleExtension)
    archiveFileName = "${providers.gradleProperty("mimir.pacmanName").get()}-extension-${providers.gradleProperty("mimir.version").get()}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

val checkExtension = tasks.register<Exec>("checkExtension") {
    group = "verification"
    description = "Проверить манифест MV3 и popup"
    dependsOn(assembleExtension, "jsBrowserTest")
    commandLine("node", "scripts/check-extension.mjs", extensionDir.get().asFile.path)
}

tasks.named("check") { dependsOn(checkExtension) }
