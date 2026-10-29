package dev.mimir.build

object VersionCode {
    /**
     * Calculates Android versionCode from semantic version string.
     * Formula: major * 10000 + minor * 100 + patch
     * Suffixes like -beta.1 or +build are stripped.
     */
    fun versionCodeOf(version: String): Int {
        val cleanVersion = version.substringBefore('-').substringBefore('+')
        val parts = cleanVersion.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }
}

fun versionCodeOf(version: String): Int = VersionCode.versionCodeOf(version)
