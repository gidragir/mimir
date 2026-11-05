plugins {
    id("mimir.jvm")
    kotlin("plugin.serialization")
    application
    alias(libs.plugins.buildconfig)
}

// Вшитые значения клиентов Google для релизных сборок; без переменных окружения — пустые строки.
buildConfig {
    packageName("dev.mimir.daemon")
    className("DaemonBuildConfig")
    listOf("DESKTOP_CLIENT_ID", "DESKTOP_CLIENT_SECRET", "DEVICE_CLIENT_ID", "DEVICE_CLIENT_SECRET").forEach {
        buildConfigField("GOOGLE_$it", providers.environmentVariable("MIMIR_BUILD_GOOGLE_$it").orElse(""))
    }
}

application {
    mainClass = "dev.mimir.daemon.MainKt"
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        val mimirEnv = providers.environmentVariablesPrefixedBy("MIMIR_").get()
        environment.keys.removeIf { it.startsWith("MIMIR_") }
        environment(mimirEnv)
    }
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:drive"))
    api(project(":libs:runtime"))
    api(project(":libs:client"))
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
}

// Web UI раздаётся демоном: бандл :web-ui попадает в ресурсы static/ только при упаковке дистрибутивов
// или явном -Pmimir.bundleWebUi=true, чтобы JVM-тесты (:apps:daemon:test, :apps:desktop:test) не тянули Wasm/Webpack.
val bundleWebUi = providers.gradleProperty("mimir.bundleWebUi").map { it.toBoolean() }.orElse(false).get() ||
    gradle.startParameter.taskNames.any { taskName ->
        val simple = taskName.substringAfterLast(':')
        simple.contains("package", ignoreCase = true) ||
            simple.contains("Distributable", ignoreCase = true) ||
            simple in setOf("installDist", "distTar", "distZip", "assembleDist")
    }
if (bundleWebUi) {
    evaluationDependsOn(":apps:web-ui")
    tasks.processResources {
        from(project(":apps:web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }
    }
}

// Для JVM-тестов (WebUiServedTest) static/index.html подкладывается из исходников :web-ui без сборки Wasm.
tasks.processTestResources {
    from(rootProject.file("apps/web-ui/src/wasmJsMain/resources")) {
        include("index.html")
        into("static")
    }
}
