pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("mimir.settings")
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mimir"

// Библиотеки (libs)
include(
    ":libs:core",
    ":libs:drive",
    ":libs:client",
    ":libs:runtime",
    ":libs:ui",
)

// Приложения и клиенты (apps); :apps:android подключается условно через mimir.settings
include(
    ":apps:desktop",
    ":apps:daemon",
    ":apps:web-ui",
    ":apps:obsidian-plugin",
    ":apps:browser-extension",
)
