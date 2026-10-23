plugins {
    id("mimir.kmp-library")
    alias(libs.plugins.buildconfig)
}

buildConfig {
    packageName("dev.mimir.core")
    buildConfigField("APP_NAME", providers.gradleProperty("mimir.appName"))
    buildConfigField("VERSION", providers.gradleProperty("mimir.version"))
    buildConfigField("APP_ID", providers.gradleProperty("mimir.appId"))
    buildConfigField("OBSIDIAN_PLUGIN_ID", providers.gradleProperty("mimir.obsidianPluginId"))
    buildConfigField("API_DEFAULT_PORT", providers.gradleProperty("mimir.apiDefaultPort").map { it.toInt() })
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
        }
    }
}
