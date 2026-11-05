package dev.mimir.core.update

import kotlin.time.Instant

enum class TargetPlatform {
    WINDOWS_X64,
    WINDOWS_ARM64,
    LINUX_X64,
    LINUX_ARM64,
    ANDROID_ANY,
    OBSIDIAN_ANY,
    BROWSER_EXTENSION_ANY,
    DOCKER_ANY,
    UNKNOWN,
}

enum class AssetKind {
    MSI,
    APK,
    OBSIDIAN_FILES,
    EXTENSION_ZIP,
    ARCH_PKG,
    DOCKER_TAG,
    GENERIC,
}

enum class ApplierCapability {
    NOTIFY_ONLY,
    DOWNLOAD_AND_LAUNCH,
    IN_PLACE_REPLACE,
}

data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val expectedSha256: String?,
    val platform: TargetPlatform,
    val kind: AssetKind,
)

data class UpdateRelease(
    val version: SemVer,
    val releaseNotes: String,
    val publishedAt: Instant,
    val htmlUrl: String,
    val assets: List<UpdateAsset>,
    val checksums: Map<String, String> = emptyMap(),
)
