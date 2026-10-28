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
    implementation(project(":core"))
    implementation(project(":drive"))
    implementation(project(":runtime"))
    api(project(":client"))
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

// Web UI раздаётся демоном: бандл :web-ui попадает в ресурсы static/
evaluationDependsOn(":web-ui")
tasks.processResources {
    from(project(":web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }
}
