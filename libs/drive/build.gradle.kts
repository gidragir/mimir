plugins {
    id("mimir.kmp-library")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":libs:core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}
