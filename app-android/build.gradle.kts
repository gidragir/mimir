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
        versionCode = 1
        versionName = appVersion
        resValue("string", "app_name", appName)
    }

    buildFeatures {
        resValues = true
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
    implementation(project(":ui"))
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
