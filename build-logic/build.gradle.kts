plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.agp)
    implementation(libs.kotlin.serialization.plugin)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }
