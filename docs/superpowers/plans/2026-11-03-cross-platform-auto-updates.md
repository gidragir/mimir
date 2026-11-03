# Cross-Platform Auto-Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a platform-independent auto-update subsystem across all Mimir targets (`core`, `runtime`, `daemon`, `app-desktop`, `app-android`, `obsidian-plugin`, `browser-extension`, CI) using Ports and Adapters, Strategy, and Unidirectional Data Flow.

**Architecture:** Core contracts (`UpdateChannel`, `UpdateApplier`, `UpdateHttpClient`) and orchestrator (`UpdateManager` with `StateFlow<UpdateState>`) live in `:core` (KMP). Platform adapters implement streaming downloads with SHA-256 validation (`WindowsMsiApplier`, `AndroidPackageInstallerApplier`, `ObsidianInPlaceApplier`, `DesktopNotifyApplier`, `WebExtensionNotifyApplier`, `DockerLogNotifyApplier`). The release pipeline (`release.yml`) is extended to publish `sha256sums.txt` and raw Obsidian files.

**Tech Stack:** Kotlin Multiplatform (JVM, Android, JS), Kotlin Coroutines & Flow, kotlinx-datetime, kotlinx-serialization, Ktor Client (in `:runtime`/`:daemon`), Compose Multiplatform (UI).

**Spec:** `docs/superpowers/specs/2026-11-03-cross-platform-auto-updates-design.md`

## Global Constraints

- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8.
- Android SDK optional: `mise run build` must succeed without SDK.
- No heavy network libraries (like Ktor) in `:core`: `:core` defines the pure `UpdateHttpClient` port.
- Version string is never hardcoded: single source of truth is `mimir.version` in `gradle.properties`.
- SHA-256 verification is mandatory before executing external installers (`msiexec`, `PackageInstaller`).
- Invariant: all shell/tool executions on host use `rtk` prefix (`rtk ./gradlew ...`).

## Review Focus

1. **Malicious or corrupted payload:** SHA-256 mismatch must immediately discard downloaded asset and transition to `UpdateState.Error` without invoking installer.
2. **GitHub API rate limit (403 / 429):** Must handle rate-limiting gracefully, parse `Retry-After` if present, utilize `If-None-Match` (ETag), and never crash or loop endlessly.
3. **Partial network drop during download:** Streaming download interrupted midway must fail cleanly, delete incomplete local temp files, and allow retry without corrupt state.
4. **Active vault synchronization:** Invoking an update must not interrupt an in-progress sync engine run or corrupt SQLite/index state.
5. **No-daemon standalone mode:** Obsidian and Browser Extension clients must function 100% autonomously without a running daemon.

---

### Task 1: Core Domain Models & SemVer Parser (`:core`)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/SemVer.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateModels.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateHttpClient.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/update/SemVerTest.kt`

**Interfaces:**
- Produces:
  - `class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer>` with `fun isNewerThan(other: SemVer): Boolean` and `companion object { fun parse(value: String): SemVer }`
  - `enum class TargetPlatform`, `enum class AssetKind`, `enum class ApplierCapability`
  - `data class UpdateAsset(...)`, `data class UpdateRelease(...)`
  - `interface UpdateHttpClient` and `interface UpdateHttpResponse`

- [ ] **Step 1: Write failing test for SemVer**

```kotlin
package dev.mimir.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun parseStandardVersions() {
        val v1 = SemVer.parse("0.1.0")
        assertEquals(0, v1.major)
        assertEquals(1, v1.minor)
        assertEquals(0, v1.patch)
        assertEquals(null, v1.preRelease)

        val v2 = SemVer.parse("v1.23.456-beta.2")
        assertEquals(1, v2.major)
        assertEquals(23, v2.minor)
        assertEquals(456, v2.patch)
        assertEquals("beta.2", v2.preRelease)
    }

    @Test
    fun comparisonRules() {
        assertTrue(SemVer.parse("0.2.0").isNewerThan(SemVer.parse("0.1.9")))
        assertTrue(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("0.9.9")))
        assertFalse(SemVer.parse("0.1.0").isNewerThan(SemVer.parse("0.1.0")))
        assertFalse(SemVer.parse("0.1.0").isNewerThan(SemVer.parse("0.1.1")))
        // Release is newer than pre-release of the same version
        assertTrue(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("1.0.0-rc.1")))
    }

    @Test
    fun invalidVersionsThrow() {
        assertFailsWith<IllegalArgumentException> { SemVer.parse("not-a-version") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.0") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.SemVerTest"`  
Expected: Compilation error or test failure (class not found).

- [ ] **Step 3: Implement `SemVer.kt`, `UpdateModels.kt`, and `UpdateHttpClient.kt`**

Implement `SemVer` with regex-based parsing `^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$`, standard comparison logic (`major`, `minor`, `patch`, pre-release rules). Implement domain models and `UpdateHttpClient` interface in `dev.mimir.core.update`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.SemVerTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add core/src/commonMain/kotlin/dev/mimir/core/update/ core/src/commonTest/kotlin/dev/mimir/core/update/
rtk git commit -m "feat(core): add SemVer parser, update models and UpdateHttpClient port"
```

---

### Task 2: GitHub Releases Channel & Checksum Parser (`:core`)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateChannel.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/GitHubReleasesChannel.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/update/FakeUpdateHttpClient.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/update/GitHubReleasesChannelTest.kt`

**Interfaces:**
- Consumes: `SemVer`, `UpdateModels`, `UpdateHttpClient` (from Task 1)
- Produces:
  - `interface UpdateChannel { suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult }`
  - `class GitHubReleasesChannel(val owner: String, val repo: String, val httpClient: UpdateHttpClient) : UpdateChannel`

- [ ] **Step 1: Write failing test with FakeUpdateHttpClient**

```kotlin
package dev.mimir.core.update

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GitHubReleasesChannelTest {
    @Test
    fun returnsNoUpdateWhenSameOrOlder() = runTest {
        val fakeClient = FakeUpdateHttpClient(
            responses = mapOf(
                "https://api.github.com/repos/mimir-org/mimir/releases/latest" to UpdateHttpResponse(
                    statusCode = 200,
                    headers = mapOf("ETag" to "\"tag-123\""),
                    bodyText = """{"tag_name": "v0.1.0", "body": "Notes", "published_at": "2026-11-03T00:00:00Z", "html_url": "https://...", "assets": []}"""
                )
            )
        )
        val channel = GitHubReleasesChannel("mimir-org", "mimir", fakeClient)
        val result = channel.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.NoUpdate>(result)
    }

    @Test
    fun returnsUpdateAvailableAndMatchesChecksums() = runTest {
        val releaseJson = """
        {
            "tag_name": "v0.2.0",
            "body": "New release features",
            "published_at": "2026-11-03T12:00:00Z",
            "html_url": "https://github.com/mimir-org/mimir/releases/tag/v0.2.0",
            "assets": [
                {
                    "name": "Mimir-0.2.0.msi",
                    "browser_download_url": "https://github.com/.../Mimir-0.2.0.msi",
                    "size": 10485760
                },
                {
                    "name": "sha256sums.txt",
                    "browser_download_url": "https://github.com/.../sha256sums.txt",
                    "size": 128
                }
            ]
        }
        """.trimIndent()
        val checksums = "a1b2c3d4e5f6...  Mimir-0.2.0.msi\n"
        val fakeClient = FakeUpdateHttpClient(
            responses = mapOf(
                "https://api.github.com/repos/mimir-org/mimir/releases/latest" to UpdateHttpResponse(200, emptyMap(), releaseJson),
                "https://github.com/.../sha256sums.txt" to UpdateHttpResponse(200, emptyMap(), checksums)
            )
        )
        val channel = GitHubReleasesChannel("mimir-org", "mimir", fakeClient)
        val result = channel.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(result)
        assertEquals("0.2.0", result.release.version.toString())
        assertNotNull(result.matchedAsset)
        assertEquals(AssetKind.MSI, result.matchedAsset.kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.GitHubReleasesChannelTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `GitHubReleasesChannel.kt`**

Implement GitHub releases JSON parsing using `kotlinx.serialization.json.Json`, regex matching for platform assets, SHA-256 checksum mapping, and conditional ETag handling (`If-None-Match`).

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.GitHubReleasesChannelTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add core/src/commonMain/kotlin/dev/mimir/core/update/UpdateChannel.kt core/src/commonMain/kotlin/dev/mimir/core/update/GitHubReleasesChannel.kt core/src/commonTest/kotlin/dev/mimir/core/update/
rtk git commit -m "feat(core): implement GitHubReleasesChannel and asset matching with SHA-256 checksum parsing"
```

---

### Task 3: Update State Machine & UpdateManager Orchestrator (`:core`)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateState.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateApplier.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/UpdateManager.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/update/DefaultUpdateManager.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/update/UpdateManagerTest.kt`

**Interfaces:**
- Consumes: `UpdateChannel`, `UpdateModels`, `SemVer` (from Tasks 1-2)
- Produces:
  - `interface UpdateApplier` (`download(...)`, `apply(...)`, `capability`)
  - `sealed interface DownloadedAsset` (`LocalFile`, `MemoryBundle`, `Virtual`)
  - `interface UpdateManager` (`state: StateFlow<UpdateState>`, `checkForUpdates()`, `downloadUpdate()`, `applyUpdate()`, `dismiss()`)
  - `class DefaultUpdateManager(...) : UpdateManager`

- [ ] **Step 1: Write failing test for state transitions**

```kotlin
package dev.mimir.core.update

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateManagerTest {
    @Test
    fun fullUpdateCycleTransitions() = runTest {
        val fakeChannel = FakeUpdateChannel(
            result = UpdateCheckResult.UpdateAvailable(
                release = UpdateRelease(
                    version = SemVer.parse("0.2.0"),
                    releaseNotes = "Notes",
                    publishedAt = kotlinx.datetime.Instant.DISTANT_PAST,
                    htmlUrl = "https://...",
                    assets = emptyList()
                ),
                matchedAsset = UpdateAsset("app.msi", "https://...", 1000L, "hash", TargetPlatform.WINDOWS_X64, AssetKind.MSI)
            )
        )
        val fakeApplier = FakeUpdateApplier(capability = ApplierCapability.DOWNLOAD_AND_LAUNCH)
        val manager = DefaultUpdateManager(
            currentVersion = SemVer.parse("0.1.0"),
            targetPlatform = TargetPlatform.WINDOWS_X64,
            channel = fakeChannel,
            applier = fakeApplier
        )

        assertIs<UpdateState.Idle>(manager.state.value)

        manager.checkForUpdates()
        assertIs<UpdateState.Available>(manager.state.value)

        val downloadResult = manager.downloadUpdate()
        assertTrue(downloadResult.isSuccess)
        assertIs<UpdateState.ReadyToInstall>(manager.state.value)

        val applyResult = manager.applyUpdate()
        assertIs<UpdateApplyResult.LaunchedInstaller>(applyResult)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.UpdateManagerTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `UpdateState.kt`, `UpdateApplier.kt`, and `DefaultUpdateManager.kt`**

Implement `DefaultUpdateManager` managing `MutableStateFlow<UpdateState>`, thread-safe mutex execution for check/download/apply, progress reporting forwarding `(Float, Long, Long)` to `UpdateState.Downloading`, and dismiss reset to `UpdateState.Idle`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :core:commonTest --tests "dev.mimir.core.update.UpdateManagerTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add core/src/commonMain/kotlin/dev/mimir/core/update/
rtk git commit -m "feat(core): implement UpdateManager state machine and orchestrator"
```

---

### Task 4: Runtime Integration & Ktor Network Adapter (`:runtime`)

**Files:**
- Create: `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClient.kt`
- Modify: `runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt`
- Test: `runtime/src/jvmTest/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClientTest.kt`

**Interfaces:**
- Consumes: `:core` `UpdateHttpClient`, `UpdateHttpResponse`, `UpdateManager`
- Produces:
  - `class KtorUpdateHttpClient(val client: io.ktor.client.HttpClient) : UpdateHttpClient`
  - `MimirRuntime.updateManager: UpdateManager` property

- [ ] **Step 1: Write failing test for Ktor streaming and SHA-256 validation**

```kotlin
package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorUpdateHttpClientTest {
    @Test
    fun streamsBytesAndComputesHash() = runTest {
        val payload = "Mimir installer test payload bytes".encodeToByteArray()
        val expectedSha = Sha256.digest(payload).joinToString("") { "%02x".format(it) }

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { respond(payload) }
            }
        }
        val updateClient = KtorUpdateHttpClient(client)
        val hasher = Sha256.Hasher()
        var received = 0L

        val result = updateClient.downloadStream("https://example.com/asset.msi") { chunk, read, total ->
            hasher.update(chunk)
            received = read
        }
        assertTrue(result.isSuccess)
        assertEquals(payload.size.toLong(), received)
        val actualSha = hasher.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expectedSha, actualSha)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :runtime:jvmTest --tests "dev.mimir.runtime.update.KtorUpdateHttpClientTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `KtorUpdateHttpClient.kt` and integrate into `MimirRuntime.kt`**

Implement `KtorUpdateHttpClient` using Ktor's `bodyAsChannel().readRemaining()` chunked reading. Expose `updateManager` in `MimirRuntime`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :runtime:jvmTest --tests "dev.mimir.runtime.update.KtorUpdateHttpClientTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add runtime/src/
rtk git commit -m "feat(runtime): implement KtorUpdateHttpClient and hook UpdateManager into MimirRuntime"
```

---

### Task 5: Daemon API Routes & Configuration (`:daemon`)

**Files:**
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/UpdateDto.kt`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/UpdateRoutesTest.kt`

**Interfaces:**
- Consumes: `UpdateManager`, `UpdateState` from `:core` and `:runtime`
- Produces:
  - Endpoints: `GET /api/v1/update`, `POST /api/v1/update/check`, `POST /api/v1/update/download`, `POST /api/v1/update/apply`
  - DTOs: `UpdateStatusDto`

- [ ] **Step 1: Write failing test for update endpoints in Ktor Test Host**

```kotlin
package dev.mimir.daemon

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateRoutesTest {
    @Test
    fun getUpdateStatusReturnsCurrentVersionAndState() = testApplication {
        application {
            // configure test routes with mock daemon service
        }
        val response = client.get("/api/v1/update")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"currentVersion\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.UpdateRoutesTest"`  
Expected: 404 Not Found on `/api/v1/update`.

- [ ] **Step 3: Implement `UpdateDto.kt` and update routes in `Routes.kt`**

Map `UpdateManager.state` to JSON DTOs and handle `POST` commands.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.UpdateRoutesTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/src/
rtk git commit -m "feat(daemon): add /api/v1/update endpoints and update status DTOs"
```

---

### Task 6: Desktop Appliers & Windows Process Coordination (`:app-desktop`)

**Files:**
- Create: `app-desktop/src/jvmMain/kotlin/dev/mimir/desktop/update/WindowsMsiApplier.kt`
- Create: `app-desktop/src/jvmMain/kotlin/dev/mimir/desktop/update/DesktopNotifyApplier.kt`
- Test: `app-desktop/src/jvmTest/kotlin/dev/mimir/desktop/update/WindowsMsiApplierTest.kt`

**Interfaces:**
- Consumes: `:core` `UpdateApplier`, `UpdateAsset`, `DownloadedAsset`
- Produces:
  - `class WindowsMsiApplier(val httpClient: UpdateHttpClient, val tempDir: Path, val shutdownHook: suspend () -> Unit) : UpdateApplier`
  - `class DesktopNotifyApplier(val browserOpener: (String) -> Unit) : UpdateApplier`

- [ ] **Step 1: Write failing test for WindowsMsiApplier**

```kotlin
package dev.mimir.desktop.update

import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsMsiApplierTest {
    @Test
    fun validatesSha256AndBuildsProcessCommand() = runTest {
        var shutdownCalled = false
        var executedCommand: List<String>? = null
        val applier = WindowsMsiApplier(
            httpClient = FakeUpdateHttpClient(),
            tempDir = java.nio.file.Files.createTempDirectory("mimir-test"),
            processLauncher = { cmd -> executedCommand = cmd; 0 },
            shutdownHook = { shutdownCalled = true }
        )
        val asset = UpdateAsset("Mimir-0.2.0.msi", "https://...", 1024L, "valid-sha", TargetPlatform.WINDOWS_X64, AssetKind.MSI)
        val downloaded = DownloadedAsset.LocalFile("/tmp/Mimir-0.2.0.msi", "valid-sha", asset)

        val result = applier.apply(downloaded)
        assertIs<UpdateApplyResult.LaunchedInstaller>(result)
        assertTrue(shutdownCalled)
        assertTrue(executedCommand?.contains("msiexec") == true)
        assertTrue(executedCommand?.contains("/i") == true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.update.WindowsMsiApplierTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `WindowsMsiApplier.kt` and `DesktopNotifyApplier.kt`**

Implement streaming to temp file, strict SHA-256 match, external daemon shutdown triggering, and `ProcessBuilder("msiexec", "/i", path, "/passive")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.update.WindowsMsiApplierTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add app-desktop/src/
rtk git commit -m "feat(desktop): implement WindowsMsiApplier with SHA-256 check and DesktopNotifyApplier"
```

---

### Task 7: Android APK Applier (`:app-android`)

**Files:**
- Create: `app-android/src/main/kotlin/dev/mimir/app/update/AndroidPackageInstallerApplier.kt`
- Test: `app-android/src/test/kotlin/dev/mimir/app/update/AndroidPackageInstallerApplierTest.kt`

**Interfaces:**
- Consumes: `:core` `UpdateApplier`, `UpdateAsset`, `DownloadedAsset`
- Produces:
  - `class AndroidPackageInstallerApplier(val context: android.content.Context, val httpClient: UpdateHttpClient) : UpdateApplier`

- [ ] **Step 1: Write failing test for AndroidPackageInstallerApplier intent generation**

```kotlin
package dev.mimir.app.update

import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import org.junit.Test
import kotlin.test.assertIs

class AndroidPackageInstallerApplierTest {
    @Test
    fun generatesActionViewIntentForApk() {
        // verify FileProvider URI intent creation
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :app-android:testReleaseUnitTest --tests "dev.mimir.app.update.AndroidPackageInstallerApplierTest"`  
Expected: Compilation failure (or skipped if Android SDK absent).

- [ ] **Step 3: Implement `AndroidPackageInstallerApplier.kt`**

Stream APK to `context.cacheDir/updates/`, verify SHA-256, resolve content URI via `FileProvider`, launch Intent `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-android:testReleaseUnitTest --tests "dev.mimir.app.update.AndroidPackageInstallerApplierTest"`  
Expected: PASS (or cleanly skipped per AGENTS.md rule if Android SDK absent).

- [ ] **Step 5: Commit**

```bash
rtk git add app-android/src/
rtk git commit -m "feat(android): implement AndroidPackageInstallerApplier with FileProvider intent"
```

---

### Task 8: Obsidian Plugin In-Place Applier & Web Extension Notify Applier (`:obsidian-plugin`, `:browser-extension`)

**Files:**
- Create: `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/FetchUpdateHttpClient.kt`
- Create: `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/ObsidianInPlaceApplier.kt`
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/browser/update/WebExtensionNotifyApplier.kt`
- Test: `obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/update/ObsidianInPlaceApplierTest.kt`

**Interfaces:**
- Consumes: `:core` `UpdateApplier`, `UpdateHttpClient`, `DownloadedAsset.MemoryBundle`
- Produces:
  - `class ObsidianInPlaceApplier(val pluginDir: String, val vaultAdapter: ObsidianVaultAdapter) : UpdateApplier`
  - `class WebExtensionNotifyApplier : UpdateApplier`

- [ ] **Step 1: Write failing test for ObsidianInPlaceApplier**

```kotlin
package dev.mimir.obsidian.update

import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObsidianInPlaceApplierTest {
    @Test
    fun writesMainJsAndManifestInPlace() = runTest {
        val writtenFiles = mutableMapOf<String, ByteArray>()
        val applier = ObsidianInPlaceApplier(
            pluginDir = ".obsidian/plugins/mimir-sync",
            writer = { path, bytes -> writtenFiles[path] = bytes },
            notifier = {}
        )
        val asset = UpdateAsset("main.js", "https://...", 500L, null, TargetPlatform.OBSIDIAN_ANY, AssetKind.OBSIDIAN_FILES)
        val bundle = DownloadedAsset.MemoryBundle(
            files = mapOf("main.js" to "console.log()".encodeToByteArray()),
            asset = asset
        )
        val result = applier.apply(bundle)
        assertIs<UpdateApplyResult.Success>(result)
        assertEquals(1, writtenFiles.size)
        assertTrue(writtenFiles.containsKey(".obsidian/plugins/mimir-sync/main.js"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :obsidian-plugin:jsTest --tests "dev.mimir.obsidian.update.ObsidianInPlaceApplierTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `FetchUpdateHttpClient.kt`, `ObsidianInPlaceApplier.kt`, and `WebExtensionNotifyApplier.kt`**

Implement JS `fetch` wrapper with streaming reader, direct file overwrite in Obsidian plugin directory, and Chrome badge setter in extension.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :obsidian-plugin:jsTest --tests "dev.mimir.obsidian.update.ObsidianInPlaceApplierTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add obsidian-plugin/src/ browser-extension/src/
rtk git commit -m "feat(js): implement Obsidian in-place applier and browser extension notify applier"
```

---

### Task 9: UI Components & Settings Integration (`:ui`)

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/model/DaemonServiceUiModel.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/AboutUpdateCard.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardTest.kt`

**Interfaces:**
- Consumes: `UpdateState`, `UpdateManager`
- Produces:
  - `MimirUiModel.updateState: StateFlow<UpdateState>`
  - `MimirUiModel.checkForUpdates()`
  - Composable `AboutUpdateCard(...)` with buttons for Check, Download, Install, Open Release URL

- [ ] **Step 1: Write failing Compose UI test for update card states**

```kotlin
package dev.mimir.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import dev.mimir.ui.screens.AboutUpdateCard
import org.junit.Rule
import org.junit.Test

class UpdateCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun displaysUpdateAvailableBannerAndInstallButton() {
        val state = UpdateState.Available(
            release = UpdateRelease(
                version = SemVer.parse("0.2.0"),
                releaseNotes = "Fixes bug",
                publishedAt = kotlinx.datetime.Instant.DISTANT_PAST,
                htmlUrl = "https://...",
                assets = emptyList()
            ),
            asset = null,
            capability = ApplierCapability.DOWNLOAD_AND_LAUNCH
        )
        rule.setContent {
            AboutUpdateCard(
                currentVersion = "0.1.0",
                updateState = state,
                onCheckUpdates = {},
                onDownloadUpdate = {},
                onApplyUpdate = {},
                onDismiss = {}
            )
        }
        rule.onNodeWithText("Доступна версия 0.2.0").assertExists()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.UpdateCardTest"`  
Expected: Compilation failure.

- [ ] **Step 3: Implement `AboutUpdateCard.kt` and integrate into `MimirUiModel`**

Design UI card according to Mimir tokens (`MimirButton`, `MimirCard`, Obsidian colors) and bind state transitions.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.UpdateCardTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/
rtk git commit -m "feat(ui): add AboutUpdateCard and integrate UpdateState into MimirUiModel"
```

---

### Task 10: CI Release Pipeline Extension (`.github/workflows/release.yml`)

**Files:**
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Produces:
  - Published asset `sha256sums.txt`
  - Published uncompressed assets `main.js` and `manifest.json` for Obsidian

- [ ] **Step 1: Inspect and update `.github/workflows/release.yml`**

Add steps to:
1. Export uncompressed `main.js` and `manifest.json` in `obsidian-plugin` build job.
2. Generate `sha256sums.txt` in the `publish` job before uploading files to GitHub Release:
```yaml
      - name: Generate Checksums
        run: |
          cd dist
          sha256sum * > sha256sums.txt
```

- [ ] **Step 2: Verify GitHub Actions syntax**

Run: `rtk git diff .github/workflows/release.yml`  
Expected: Clean syntax additions without breaking existing matrix jobs.

- [ ] **Step 3: Run whole build and verification**

Run: `rtk ./gradlew check --dry-run`  
Expected: Successful task graph resolution.

- [ ] **Step 4: Commit**

```bash
rtk git add .github/workflows/release.yml
rtk git commit -m "ci: publish sha256sums.txt and uncompressed obsidian plugin assets in release"
```
