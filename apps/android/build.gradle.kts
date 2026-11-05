plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

val appName = providers.gradleProperty("mimir.appName").get()
val appId = providers.gradleProperty("mimir.appId").get()
val appVersion = providers.gradleProperty("mimir.version").get()
val pacmanName = providers.gradleProperty("mimir.pacmanName").get()

base {
    archivesName.set("$pacmanName-$appVersion")
}

// Ключ подписи берётся из окружения; при отсутствии любого из параметров release остаётся неподписанным.
val keystorePath = providers.environmentVariable("MIMIR_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
val keystorePassword = providers.environmentVariable("MIMIR_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val keyAlias = providers.environmentVariable("MIMIR_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val keyPassword = providers.environmentVariable("MIMIR_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val isSignedRelease = keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null

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

    lint {
        checkReleaseBuilds = false
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
        if (isSignedRelease) {
            create("release") {
                storeFile = file(checkNotNull(keystorePath))
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            if (isSignedRelease) signingConfig = signingConfigs.getByName("release")
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = false
    }
}

val releaseSourceApkGlob = if (isSignedRelease) "*-release.apk" else "*-release-unsigned.apk"
val releaseApkFileName = if (isSignedRelease) {
    "$pacmanName-$appVersion.apk"
} else {
    "$pacmanName-$appVersion-unsigned.apk"
}

tasks.register<Sync>("packageReleaseApk") {
    group = "build"
    description = "Собрать релизный APK и скопировать в build/distributions с каноническим именем"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include(releaseSourceApkGlob)
        rename(".*\\.apk", releaseApkFileName)
    }
    into(layout.buildDirectory.dir("distributions"))
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:drive"))
    implementation(project(":libs:runtime"))
    implementation(project(":libs:ui"))
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core)

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
    // Conscrypt грузит нативную библиотеку, Robolectric использует sun.misc.Unsafe: на JDK 27 иначе предупреждения.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

val asmVersion = libs.versions.asm.get()
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(asmVersion)
        }
    }
}
