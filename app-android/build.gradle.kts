plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

val appName = providers.gradleProperty("mimir.appName").get()
val appId = providers.gradleProperty("mimir.appId").get()
val appVersion = providers.gradleProperty("mimir.version").get()

// Ключ подписи берётся из окружения; без него release остаётся неподписанным.
val keystorePath = providers.environmentVariable("MIMIR_KEYSTORE_PATH").orNull

android {
    namespace = "${providers.gradleProperty("mimir.group").get()}.android"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = dev.mimir.build.VersionCode.versionCodeOf(appVersion)
        versionName = appVersion
        resValue("string", "app_name", appName)

        val desktopClientId = providers.environmentVariable("MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID")
            .orElse(providers.environmentVariable("MIMIR_GOOGLE_DESKTOP_CLIENT_ID"))
            .orElse("")
            .get()
        val desktopClientSecret = providers.environmentVariable("MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_SECRET")
            .orElse(providers.environmentVariable("MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET"))
            .orElse("")
            .get()
        buildConfigField("String", "GOOGLE_DESKTOP_CLIENT_ID", "\"$desktopClientId\"")
        buildConfigField("String", "GOOGLE_DESKTOP_CLIENT_SECRET", "\"$desktopClientSecret\"")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        resValues = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("MIMIR_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("MIMIR_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("MIMIR_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":drive"))
    implementation(project(":runtime"))
    implementation(project(":ui"))
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.androidx.browser)

    implementation(libs.work.runtime.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.play.services.auth)
    implementation(libs.coroutines.play.services)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("org.objectweb.asm.experimental", "true")
}

val asmVersion = libs.versions.asm.get()
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(asmVersion)
        }
    }
}
