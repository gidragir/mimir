plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    alias(libs.plugins.buildconfig)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

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

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
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
} else {
    // Без бандла static/ пуст: тест раздачи Web UI проверяется только с -Pmimir.bundleWebUi=true.
    tasks.test { filter { excludeTestsMatching("*WebUiServedTest") } }
}
