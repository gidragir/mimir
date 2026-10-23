plugins {
    id("mimir.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
        }
    }
}
