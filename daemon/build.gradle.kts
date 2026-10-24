plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

application {
    mainClass = "dev.mimir.daemon.MainKt"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":drive"))
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }

// Web UI раздаётся демоном: бандл :web-ui попадает в ресурсы static/
evaluationDependsOn(":web-ui")
tasks.processResources {
    from(project(":web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }
}
