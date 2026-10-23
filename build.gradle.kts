// Плагины объявлены здесь с apply false, чтобы все модули делили один classloader.
plugins {
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.buildconfig) apply false
}
