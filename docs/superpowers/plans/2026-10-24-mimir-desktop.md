# Mimir Stage 5: Desktop App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Stage 5 Mimir desktop application for Linux (Arch / Wayland niri / X11) and Windows, providing Compose Multiplatform UI (`ui`), D-Bus StatusNotifierItem & AWT tray (`app-desktop`), in-process `DaemonRuntime` with HTTP fallback, first-run setup wizard, conflict resolution center, and canary password verification.

**Architecture:** The desktop app embeds `DaemonRuntime` in-process with reactive `StateFlow` and hosts Ktor HTTP API on port 47821 (falling back to `HttpDaemonClient` if port is occupied). The UI module (`ui`) is pure KMP, depending only on `:core` and defining `MimirUiModel`. `app-desktop` binds `:ui` and `:daemon` via `DaemonServiceUiModel` and provides platform-native tray icons (D-Bus StatusNotifierItem via pure JVM Unix Domain Sockets on Linux, AWT SystemTray on Windows).

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2, Coroutines 1.10.2, Ktor 3.6.0, `dbus-java-core` / `dbus-java-transport-native-unixsocket` 5.2.2, JUnit 4, Kotlinx IO 0.8.2.

**Spec:** `docs/superpowers/specs/2026-10-24-mimir-desktop-design.md` (Parent spec: `docs/superpowers/specs/2026-10-23-mimir-design.md`).

## Global Constraints

- **Exact status label:** Before connection, status string is strictly `"Mimir: not connected"` (`SyncStatus.NotConfigured.label()`).
- **Dependency hierarchy:** Dependencies flow strictly downward: `app-desktop` -> `ui` & `daemon` -> `drive` -> `core`. Module `ui` must NOT depend on `daemon`.
- **Zero native C binaries:** All Linux D-Bus communication must use pure JVM Unix domain sockets (`dbus-java-transport-native-unixsocket`).
- **Dependencies & Identifiers:** Dependency versions strictly in `gradle/libs.versions.toml`; identifiers strictly in `gradle.properties`.
- **TDD:** Write failing tests first before implementing product code (`desktopTest` in `ui`, `test` in `daemon` and `app-desktop`).
- **Zero secrets in repo:** All tests run without external credentials or Android SDK.
- **Git workflow:** Conventional Commits, work on feature branch `feat/desktop-app`, release changes documented via `pnpm changeset`.

## Review Focus

1. **Incorrect encryption password entered during setup/unlock:** Canary check against `.mimir_canary` fails MAC verification in `SecretBox.open`, throws `InvalidPasswordException`, and UI displays error without corrupting remote files or creating split-brain.
2. **Local port 47821 already bound by external daemon:** `DaemonServiceFactory` detects existing Mimir daemon via `GET /health` and connects via `HttpDaemonClient` instead of crashing with exit code 2.
3. **Closing main window when tray is active:** Window hides (`visible = false`) and sync continues; clicking tray icon restores window; when tray is unavailable (`NoTray`), closing window terminates app.
4. **Invalid or non-existent vault path selected in wizard:** Wizard validates path existence before saving to `config.json` and displays inline error on invalid path.
5. **Wayland environment without running StatusNotifierWatcher:** D-Bus registration detects absent watcher or bus error and safely falls back to `NoTray` without crashing.

---

### Task 1: Canary Check & Password Verification in `DaemonRuntime`

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt:96-106`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt:25-28`
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt` (move `markWrittenBySync` after `atomicMove`)
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/Canary.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/CanaryCheckTest.kt`

**Interfaces:**
- Consumes: `Cipher`, `RemoteStorage`, `DaemonConfig`
- Produces: `class InvalidPasswordException(message: String) : IllegalArgumentException(message)`, `suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path)`

- [ ] **Step 1: Write failing CanaryCheckTest**

```kotlin
// In daemon/src/test/kotlin/dev/mimir/daemon/CanaryCheckTest.kt
package dev.mimir.daemon

import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemoteFileId
import dev.mimir.core.RemoteStorage
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanaryCheckTest {
    private class FakeRemote : RemoteStorage {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun list(): List<RemoteEntry> = files.map { (p, b) -> RemoteEntry(RemoteFileId("id:$p"), p, b.size.toLong(), null, null) }
        override suspend fun getEntry(path: String): RemoteEntry? = files[path]?.let { RemoteEntry(RemoteFileId("id:$path"), path, it.size.toLong(), null, null) }
        override suspend fun read(id: RemoteFileId): Source = Buffer().also { it.write(files.getValue(id.value.removePrefix("id:"))) }
        override suspend fun write(path: String, data: Source): RemoteEntry {
            files[path] = data.readByteArray()
            return RemoteEntry(RemoteFileId("id:$path"), path, files[path]!!.size.toLong(), null, null)
        }
        override suspend fun delete(id: RemoteFileId) { files.remove(id.value.removePrefix("id:")) }
        override fun changes(startPageToken: String?) = emptyFlow<Nothing>()
    }

    @Test
    fun `initializes canary when remote is empty`() = runTest {
        val remote = FakeRemote()
        val cipher = RcloneCipher("correct-pass")
        val dir = Files.createTempDirectory("mimir-test-canary")
        verifyOrInitCanary(remote, cipher, dir)
        assertTrue(remote.files.containsKey(".mimir_canary"))
    }

    @Test
    fun `fails unlock when wrong password is used on existing canary`() = runTest {
        val remote = FakeRemote()
        val cipherA = RcloneCipher("password-A")
        val dir = Files.createTempDirectory("mimir-test-canary")
        verifyOrInitCanary(remote, cipherA, dir)

        val cipherB = RcloneCipher("password-B-wrong")
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, cipherB, dir)
        }
    }

    @Test
    fun `fails unlock when remote has files but no canary`() = runTest {
        val remote = FakeRemote()
        remote.files["some_existing_file"] = byteArrayOf(1, 2, 3)
        val cipher = RcloneCipher("any-pass")
        val dir = Files.createTempDirectory("mimir-test-canary")
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, cipher, dir)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.CanaryCheckTest"`  
Expected: FAIL (unresolved reference: `verifyOrInitCanary`, `InvalidPasswordException`)

- [ ] **Step 3: Implement Canary and integrate in `DaemonRuntime` and `FileSystemLocalVault`**

1. Create `Canary.kt` with `CANARY_FILENAME = ".mimir_canary"`, `CANARY_MAGIC = "mimir-vault-canary"`, and `verifyOrInitCanary(remote, cipher, configDir)`.
2. Update `DaemonRuntime.initialize()` and `DaemonRuntime.unlock()` to call `verifyOrInitCanary`.
3. In `FileSystemLocalVault.kt`: ensure `watcher?.markWrittenBySync(relativePath)` is called strictly AFTER `SystemFileSystem.atomicMove(...)`.
4. In `DaemonConfig.kt`: add check for `passwordFile` POSIX permissions (if POSIX supported and file has group/other readable bits, log warning or fail).

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.CanaryCheckTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/src/main/kotlin/dev/mimir/daemon/Canary.kt daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt daemon/src/test/kotlin/dev/mimir/daemon/CanaryCheckTest.kt
rtk git commit -m "feat(daemon): add canary password verification and atomicMove suppression fix"
```

---

### Task 2: Dynamic Vault Configuration & Conflict Caching in `Daemon`

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt:9-28`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt:63-125`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt:40-65`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt:90-198`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/ConfigureVaultTest.kt`

**Interfaces:**
- Consumes: `DaemonConfig`, `DaemonService`
- Produces: `DaemonConfig.save(configDir: String?)`, `DaemonService.configureVault(vaultPath: String)`, `val conflicts: StateFlow<List<ConflictFileEntry>>`, `POST /api/v1/vault`

- [ ] **Step 1: Write failing ConfigureVaultTest**

```kotlin
// In daemon/src/test/kotlin/dev/mimir/daemon/ConfigureVaultTest.kt
package dev.mimir.daemon

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigureVaultTest {
    @Test
    fun `save and reload daemon config`() {
        val tempDir = Files.createTempDirectory("mimir-config-test")
        val config = DaemonConfig(vaultPath = "/test/vault", configDir = tempDir.toString())
        config.save(tempDir.toString())

        val loaded = DaemonConfig.load(configDir = tempDir.toString(), env = emptyMap())
        assertEquals("/test/vault", loaded.vaultPath)
    }

    @Test
    fun `configureVault rejects non-existent directory`() = runTest {
        val tempDir = Files.createTempDirectory("mimir-config-test")
        val runtime = DaemonRuntime(DaemonConfig(configDir = tempDir.toString()))
        assertFailsWith<IllegalArgumentException> {
            runtime.configureVault("/non/existent/path/for/sure")
        }
        runtime.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.ConfigureVaultTest"`  
Expected: FAIL (unresolved reference: `save`, `configureVault`)

- [ ] **Step 3: Implement `DaemonConfig.save`, `configureVault`, conflict caching and `POST /api/v1/vault`**

1. In `DaemonConfig.kt`: implement `fun save(configDir: String? = null)` writing `Json.encodeToString(this)` atomically with POSIX 0600 permissions.
2. In `DaemonService.kt`: add `suspend fun configureVault(vaultPath: String)` and `val conflictsFlow: StateFlow<List<ConflictFileEntry>>`.
3. In `DaemonRuntime.kt`:
   - Implement `_conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())`.
   - Update `_conflicts` in `rebuild()` and upon file watcher changes.
   - Implement `configureVault(vaultPath: String)`: validate directory, save config, update internal state and call `rebuild()`.
4. In `Routes.kt`: add `post("/vault")` handling `{"vaultPath": "..."}` and calling `service.configureVault(request.vaultPath)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.ConfigureVaultTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt daemon/src/test/kotlin/dev/mimir/daemon/ConfigureVaultTest.kt
rtk git commit -m "feat(daemon): add dynamic vault configuration, conflict stateflow and vault API endpoint"
```

---

### Task 3: KMP UI Model & Status Screen in `ui`

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/model/FakeMimirUiModel.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt`
- Modify: `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt`

**Interfaces:**
- Consumes: `SyncStatus`, `BuildConfig` from `:core`
- Produces: `interface MimirUiModel`, `data class UiConflictEntry`, `data class UiDeviceCode`, `sealed interface UiDevicePoll`

- [ ] **Step 1: Write failing StatusScreenTest with `MimirUiModel`**

```kotlin
// In ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt
package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StatusScreenTest {
    @Test
    fun `shows exact not connected status for unconfigured state`() = runComposeUiTest {
        val model = FakeMimirUiModel(status = SyncStatus.NotConfigured)
        setContent { MimirApp(model) }
        onNodeWithText("Mimir: not connected").assertExists()
    }

    @Test
    fun `sync now button is enabled when idle and triggers sync`() = runComposeUiTest {
        var syncTriggered = false
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            onTriggerSync = { syncTriggered = true; true },
        )
        setContent { MimirApp(model) }
        onNodeWithText("Синхронизировать").assertIsEnabled().performClick()
        assertTrue(syncTriggered)
    }

    @Test
    fun `shows conflict badge when conflicts exist`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            conflicts = listOf(UiConflictEntry("Notes/A.md", "Notes/A (conflicted copy).md", Instant.fromEpochMilliseconds(1000))),
        )
        setContent { MimirApp(model) }
        onNodeWithText("Конфликты: 1").assertExists()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.StatusScreenTest"`  
Expected: FAIL (unresolved reference: `MimirUiModel`, `FakeMimirUiModel`)

- [ ] **Step 3: Implement `MimirUiModel`, `FakeMimirUiModel`, update `StatusScreen` and `MimirApp`**

1. Create `MimirUiModel.kt` with state flows and methods:
   - `status`, `vaultPath`, `isLocked`, `lastSyncTime`, `accountEmail`, `conflicts`
   - `triggerSync()`, `configureVault(path)`, `unlock(pass, remember)`, `resolveConflict(orig, conf, keepConf)`, `startDeviceLogin()`, `pollDeviceLogin(code)`
2. Create `FakeMimirUiModel.kt` for tests and previews.
3. Update `StatusScreen.kt` to take `MimirUiModel`, display status label, last sync time, account email, "Синхронизировать" button, and conflict badge.
4. Update `MimirApp.kt` to take `MimirUiModel` and maintain screen state (Status, Setup, Conflicts).

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.StatusScreenTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt ui/src/commonMain/kotlin/dev/mimir/ui/model/FakeMimirUiModel.kt ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt
rtk git commit -m "feat(ui): add MimirUiModel and reactive StatusScreen with sync and conflict actions"
```

---

### Task 4: First-Run Setup Wizard Screen (`SetupWizardScreen`) in `ui`

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt`
- Create: `ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt`

**Interfaces:**
- Consumes: `MimirUiModel`
- Produces: `@Composable fun SetupWizardScreen(model: MimirUiModel, onComplete: () -> Unit)`

- [ ] **Step 1: Write failing SetupWizardScreenTest**

```kotlin
// In ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt
package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.screens.SetupWizardScreen
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SetupWizardScreenTest {
    @Test
    fun `step 1 allows entering vault path`() = runComposeUiTest {
        var configuredPath = ""
        val model = FakeMimirUiModel(onConfigureVault = { configuredPath = it })
        setContent { SetupWizardScreen(model, onComplete = {}) }

        onNodeWithText("Путь к хранилищу Obsidian").assertExists()
        onNodeWithText("Введите путь к папке").performTextInput("/home/user/vault")
        onNodeWithText("Продолжить").performClick()

        assertEquals("/home/user/vault", configuredPath)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.SetupWizardScreenTest"`  
Expected: FAIL (unresolved reference: `SetupWizardScreen`)

- [ ] **Step 3: Implement `SetupWizardScreen`**

1. Create `SetupWizardScreen.kt`:
   - 3-step wizard (Step 1: Vault Path; Step 2: Google Device Code Sign-in; Step 3: Encryption Password).
   - Step 1: Text field with path validation and "Продолжить" button.
   - Step 2: Display user code, verification URL, button "Скопировать код и открыть ссылку", auto-polling indicator.
   - Step 3: Password input with masking, "Запомнить на этом устройстве" checkbox, and unlock/finish button.
2. Wire `SetupWizardScreen` into `MimirApp.kt` navigation.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.SetupWizardScreenTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt
rtk git commit -m "feat(ui): add 3-step First-Run SetupWizardScreen"
```

---

### Task 5: Conflict Resolution Center (`ConflictsScreen`) in `ui`

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt`
- Create: `ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt`

**Interfaces:**
- Consumes: `MimirUiModel`, `UiConflictEntry`
- Produces: `@Composable fun ConflictsScreen(model: MimirUiModel, onBack: () -> Unit)`

- [ ] **Step 1: Write failing ConflictsScreenTest**

```kotlin
// In ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt
package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.screens.ConflictsScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ConflictsScreenTest {
    @Test
    fun `shows empty message when no conflicts`() = runComposeUiTest {
        val model = FakeMimirUiModel(conflicts = emptyList())
        setContent { ConflictsScreen(model, onBack = {}) }
        onNodeWithText("Конфликтов не обнаружено").assertExists()
    }

    @Test
    fun `resolves conflict when keep original is clicked`() = runComposeUiTest {
        var resolvedOriginal = ""
        var resolvedKeepConflict: Boolean? = null
        val conflict = UiConflictEntry("Notes/Doc.md", "Notes/Doc (conflicted copy).md", Instant.fromEpochMilliseconds(2000))
        val model = FakeMimirUiModel(
            conflicts = listOf(conflict),
            onResolveConflict = { orig, _, keepConf ->
                resolvedOriginal = orig
                resolvedKeepConflict = keepConf
            },
        )
        setContent { ConflictsScreen(model, onBack = {}) }

        onNodeWithText("Notes/Doc.md").assertExists()
        onNodeWithText("Оставить оригинал").performClick()

        assertEquals("Notes/Doc.md", resolvedOriginal)
        assertEquals(false, resolvedKeepConflict)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.ConflictsScreenTest"`  
Expected: FAIL (unresolved reference: `ConflictsScreen`)

- [ ] **Step 3: Implement `ConflictsScreen`**

1. Create `ConflictsScreen.kt`:
   - Header with back button to StatusScreen.
   - List of conflicts with file path, conflict copy path, formatted creation time.
   - Action buttons: "Оставить оригинал" (`keepConflict = false`) and "Оставить копию" (`keepConflict = true`).
2. Integrate with `MimirApp.kt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.ConflictsScreenTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt
rtk git commit -m "feat(ui): add ConflictsScreen with resolution actions"
```

---

### Task 6: Tray Platform Integration (Linux D-Bus StatusNotifierItem + Windows AWT) in `app-desktop`

**Files:**
- Modify: `gradle/libs.versions.toml:1-48`
- Modify: `app-desktop/build.gradle.kts:12-18`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt:1-18`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt`
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt`
- Modify: `app-desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt`

**Interfaces:**
- Consumes: `java.awt.SystemTray`, `org.freedesktop.dbus`
- Produces: `interface Tray { val available: Boolean; fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit); fun update(status: String); fun close() }`, `fun createTray(): Tray`

- [ ] **Step 1: Update dependencies in `libs.versions.toml` and `app-desktop/build.gradle.kts`**

In `gradle/libs.versions.toml`:
```toml
[versions]
dbusJava = "5.2.2"

[libraries]
dbus-java-core = { module = "com.github.hypfvieh:dbus-java-core", version.ref = "dbusJava" }
dbus-java-transport-native = { module = "com.github.hypfvieh:dbus-java-transport-native-unixsocket", version.ref = "dbusJava" }
```
In `app-desktop/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core"))
    implementation(project(":daemon"))
    implementation(project(":ui"))
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native)
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 2: Write failing TrayTest**

```kotlin
// In app-desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt
package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayTest {
    @Test
    fun `createTray returns NoTray when platform not supported`() {
        val tray = createTray(osName = "UnknownOS", isLinux = false, awtTraySupported = { false })
        assertFalse(tray.available)
    }

    @Test
    fun `createTray returns AwtTray on Windows when supported`() {
        val tray = createTray(osName = "Windows 11", isLinux = false, awtTraySupported = { true })
        assertTrue(tray is AwtTray)
    }

    @Test
    fun `NoTray handles lifecycle without exceptions`() {
        val tray = NoTray()
        tray.show("Mimir: not connected", onOpen = {}, onSyncNow = {}, onQuit = {})
        tray.update("Mimir: syncing")
        tray.close()
    }
}
```

- [ ] **Step 3: Implement `DbusStatusNotifierTray` and update `AwtTray` and `createTray`**

1. Expand `Tray` interface with `onSyncNow: () -> Unit`.
2. Update `AwtTray.kt` to include MenuItem "Синхронизировать сейчас" calling `onSyncNow`.
3. Create `DbusStatusNotifierTray.kt`:
   - Connects to D-Bus Session bus (`DBusConnectionBuilder.forSessionBus().build()`).
   - Exports object `/StatusNotifierItem` implementing `StatusNotifierItem` interface.
   - Registers with `org.kde.StatusNotifierWatcher` via `RegisterStatusNotifierItem`.
   - Dispatches `Activate` to `onOpen`, and handles menu actions.
   - If bus connection or watcher registration fails, gracefully marks `available = false`.
4. Update `createTray`:
   - If Windows && AWT supported -> `AwtTray()`.
   - If Linux -> `DbusStatusNotifierTray()` (falls back to `NoTray` on failure).
   - Else -> `NoTray()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.tray.TrayTest"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add gradle/libs.versions.toml app-desktop/build.gradle.kts app-desktop/src/main/kotlin/dev/mimir/desktop/tray/ app-desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt
rtk git commit -m "feat(desktop): add D-Bus StatusNotifierItem tray for Linux and update AwtTray"
```

---

### Task 7: Runtime Factory & Desktop Wiring in `app-desktop`

**Files:**
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceUiModel.kt`
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/HttpDaemonClient.kt`
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`
- Create: `app-desktop/src/test/kotlin/dev/mimir/desktop/DaemonServiceUiModelTest.kt`
- Create: `app-desktop/src/test/kotlin/dev/mimir/desktop/DaemonServiceFactoryTest.kt`

**Interfaces:**
- Consumes: `DaemonService`, `DaemonRuntime`, `MimirUiModel`, `Tray`
- Produces: `DaemonServiceUiModel : MimirUiModel`, `HttpDaemonClient : DaemonService`, `DaemonServiceFactory.create(config: DaemonConfig)`

- [ ] **Step 1: Write failing DaemonServiceUiModelTest and DaemonServiceFactoryTest**

```kotlin
// In app-desktop/src/test/kotlin/dev/mimir/desktop/DaemonServiceUiModelTest.kt
package dev.mimir.desktop

import dev.mimir.core.SyncStatus
import dev.mimir.daemon.ConflictFileEntry
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.DevicePoll
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DaemonServiceUiModelTest {
    private class FakeDaemonService : DaemonService {
        override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        override val vaultPath = "/test/path"
        override val isLocked = false
        override val lastSyncTime: Instant? = null
        override val accountEmail = "user@test.com"
        var syncTriggered = false
        override suspend fun triggerSync(): Boolean { syncTriggered = true; return true }
        override suspend fun unlock(password: String) {}
        override suspend fun conflicts(): List<ConflictFileEntry> = emptyList()
        override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {}
        override suspend fun startDeviceLogin() = GoogleDeviceCodeResponse("dcode", "ucode", "url", 1800, 5)
        override suspend fun pollDeviceLogin(deviceCode: String) = DevicePoll.Pending
        override suspend fun configureVault(vaultPath: String) {}
        override val conflictsFlow = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
    }

    @Test
    fun `adapts DaemonService to MimirUiModel`() = runTest {
        val fakeService = FakeDaemonService()
        val adapter = DaemonServiceUiModel(fakeService, this)
        assertEquals(SyncStatus.Idle, adapter.status.value)
        assertEquals("user@test.com", adapter.accountEmail.value)
        assertTrue(adapter.triggerSync())
        assertTrue(fakeService.syncTriggered)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.DaemonServiceUiModelTest"`  
Expected: FAIL (unresolved reference: `DaemonServiceUiModel`)

- [ ] **Step 3: Implement `DaemonServiceUiModel`, `HttpDaemonClient`, `DaemonServiceFactory`, and `Main.kt`**

1. Implement `DaemonServiceUiModel : MimirUiModel` adapting `DaemonService` state flows and methods.
2. Implement `HttpDaemonClient : DaemonService` via Ktor Client polling `/api/v1/status` and `/api/v1/conflicts` every 1000 ms.
3. Implement `DaemonServiceFactory`:
   - Checks if port 47821 is responding with `/health`.
   - If responding with valid Mimir health -> returns `HttpDaemonClient`.
   - Otherwise -> initializes `DaemonRuntime` and starts embedded Ktor server on a background thread.
4. Update `Main.kt`:
   - Initializes `DaemonServiceFactory`.
   - Sets up `tray.show(status.label(), onOpen = { visible = true }, onSyncNow = { model.triggerSync() }, onQuit = ::exitApplication)`.
   - Updates `tray.update(status.label())` whenever status changes.
   - Shows window with `MimirApp(model)`.
   - Closing window hides window if `tray.available`, else exits application.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.*"`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceUiModel.kt app-desktop/src/main/kotlin/dev/mimir/desktop/HttpDaemonClient.kt app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt app-desktop/src/test/kotlin/dev/mimir/desktop/
rtk git commit -m "feat(desktop): wire DaemonServiceUiModel, HttpDaemonClient and Main application lifecycle"
```

---

### Task 8: End-to-End Verification, Changeset & CI Check

**Files:**
- Create: `.changeset/feat-desktop-app.md`
- Test: All repository tests (`mise run test`, `mise run check`)

- [ ] **Step 1: Run full repository test suite**

Run: `rtk mise run test`  
Expected: ALL tests pass across all modules (`core`, `drive`, `daemon`, `ui`, `app-desktop`).

- [ ] **Step 2: Run repository check / linter**

Run: `rtk mise run check`  
Expected: All checks pass.

- [ ] **Step 3: Add Changeset for release**

Create `.changeset/feat-desktop-app.md`:
```markdown
---
"mimir": minor
---

Stage 5: Desktop application with Compose UI, StatusNotifierItem D-Bus tray for Wayland/niri, AWT tray for Windows, First-Run Wizard, conflict resolution center, and canary encryption verification.
```

- [ ] **Step 4: Verify version synchronization**

Run: `pnpm run version:check`  
Expected: Versions match.

- [ ] **Step 5: Commit**

```bash
rtk git add .changeset/feat-desktop-app.md
rtk git commit -m "chore(release): add changeset for stage 5 desktop application"
```
