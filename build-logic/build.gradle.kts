plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.agp)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.dependency.analysis.plugin)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// build-logic подключается к Gradle Daemon; поддерживаем JVM 21+ для совместимости с IDE и Gradle
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
