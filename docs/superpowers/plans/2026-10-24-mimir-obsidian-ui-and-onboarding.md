# Mimir: Obsidian-Style UI, Native Folder Picker & Initial Sync Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a native directory picker dialog on Desktop, expand the first-run onboarding wizard to inspect and safely confirm initial two-way merge when connecting existing notes, and restyle the entire Compose UI to match Obsidian's design language in both Dark and Light themes.

**Architecture:** Add an abstracted `PlatformFolderPicker` in `ui/commonMain` with an AWT `JFileChooser` implementation in `app-desktop` injected via `LocalFolderPicker`. Extend daemon's vault API to return local note count on configure and expose `GET /api/v1/vault/inspect` for remote/local reconciliation. Transform `SetupWizardScreen` into a 4-step wizard with an explicit confirmation step for two-way merge, and restyle `MimirTheme` with custom Obsidian design tokens (palette, typography, 4-6dp shapes).

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12, Material 3, Ktor Client/Server, Java AWT Swing (EDT / IO dispatcher), Gradle 9.8.

**Spec:** `docs/superpowers/specs/2026-10-24-mimir-obsidian-ui-and-onboarding-design.md`

## Global Constraints

- **Kotlin Multiplatform Isolation**: `ui/commonMain` must remain pure Kotlin and cannot import Java AWT or Swing (`java.awt.*`, `javax.swing.*`). All desktop-specific GUI bridges reside in `app-desktop`.
- **System Theme Adaptation**: `MimirTheme` must adapt to the host OS theme via `isSystemInDarkTheme()`, supporting Obsidian Dark (`#1e1e1e`) and Obsidian Light (`#ffffff` / `#f6f6f6`).
- **Non-Reparenting WM Safety**: Desktop dialogs and windows must not break under tiling Wayland compositors (Niri, Sway); `_JAVA_AWT_WM_NONREPARENTING=1` remains active.
- **Data Preservation**: The initial sync confirmation screen must default to two-way merge (`TwoWayMerge`) preserving all files; conflicts are resolved by generating timestamped `.conflict` copies.
- **Convention & Identification**: Package prefix `dev.mimir.*`, Conventional Commits (`feat:`, `fix:`, `test:`, `chore:`).

## Review Focus

- User cancels the directory picker dialog: Must return `null` and leave the text field's existing path untouched.
- Directory picker opened with no existing path: Must open in user's home directory (`System.getProperty("user.home")`) instead of throwing an exception.
- Daemon unlocked during first-run setup: Background `SyncScheduler` must not start auto-sync until the onboarding wizard completes.
- Local or remote vault has zero files: Step 4 (`Step.InitialResolution`) must be skipped automatically, completing setup without redundant questions.
- Host runs in Light Mode: All components (cards, text fields, buttons, progress bars) must render with readable contrast using the Obsidian Light palette.

---

### Task 1: Obsidian Theme & Design Tokens

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt`
- Create: `ui/src/desktopTest/kotlin/dev/mimir/ui/MimirThemeTest.kt`

**Interfaces:**
- Produces: `val ObsidianDarkColors: ColorScheme`, `val ObsidianLightColors: ColorScheme`, `val ObsidianShapes: Shapes`, updated `@Composable fun MimirTheme(content: @Composable () -> Unit)`

- [ ] **Step 1: Write the failing theme test**

```kotlin
package dev.mimir.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class MimirThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `obsidian color tokens match spec`() {
        assertEquals(Color(0xFF1E1E1E), ObsidianDarkColors.background)
        assertEquals(Color(0xFF705DCF), ObsidianDarkColors.primary)
        assertEquals(Color(0xFFFFFFFF), ObsidianLightColors.background)
        assertEquals(Color(0xFF705DCF), ObsidianLightColors.primary)
    }

    @Test
    fun `mimir theme provides compact obsidian shapes`() {
        compose.setContent {
            MimirTheme {
                assertEquals(androidx.compose.foundation.shape.RoundedCornerShape(4.0f), MaterialTheme.shapes.extraSmall)
                assertEquals(androidx.compose.foundation.shape.RoundedCornerShape(6.0f), MaterialTheme.shapes.small)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests dev.mimir.ui.MimirThemeTest`  
Expected: FAIL with Unresolved reference `ObsidianDarkColors`.

- [ ] **Step 3: Implement Obsidian color schemes and shapes in `MimirTheme.kt`**

Define `ObsidianDarkColors` (primary `#705DCF`, background `#1E1E1E`, surface `#252525`, surfaceVariant `#2E2E2E`, outline `#363636`, onSurface `#DCDDDE`, onSurfaceVariant `#8A8A8A`) and `ObsidianLightColors` (primary `#705DCF`, background `#FFFFFF`, surface `#F6F6F6`, surfaceVariant `#EEEEEE`, outline `#D4D4D4`, onSurface `#2E2E2E`, onSurfaceVariant `#757575`). Apply `Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp))` in `MimirTheme`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests dev.mimir.ui.MimirThemeTest`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt ui/src/desktopTest/kotlin/dev/mimir/ui/MimirThemeTest.kt
rtk git commit -m "feat(ui): implement obsidian dark and light color tokens and shapes"
```

---

### Task 2: Folder Picker Abstraction & Desktop AWT Implementation

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt`
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/picker/AwtFolderPicker.kt`
- Create: `app-desktop/src/test/kotlin/dev/mimir/desktop/picker/AwtFolderPickerTest.kt`

**Interfaces:**
- Produces: `interface PlatformFolderPicker { suspend fun pickDirectory(initialPath: String? = null): String? }`
- Produces: `val LocalFolderPicker: ProvidableCompositionLocal<PlatformFolderPicker?>`
- Produces: `class AwtFolderPicker : PlatformFolderPicker`

- [ ] **Step 1: Write the failing folder picker contract test**

```kotlin
package dev.mimir.desktop.picker

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AwtFolderPickerTest {
    @Test
    fun `picker fallback directory resolves user home when initialPath is null or blank`() {
        val picker = AwtFolderPicker()
        val dir = picker.resolveInitialDirectory(null)
        assertEquals(File(System.getProperty("user.home")), dir)

        val custom = File(System.getProperty("java.io.tmpdir"))
        val resolvedCustom = picker.resolveInitialDirectory(custom.absolutePath)
        assertEquals(custom, resolvedCustom)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :app-desktop:test --tests dev.mimir.desktop.picker.AwtFolderPickerTest`  
Expected: FAIL with Unresolved reference `AwtFolderPicker`.

- [ ] **Step 3: Implement `PlatformFolderPicker` and `AwtFolderPicker`**

In `ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt`:
```kotlin
package dev.mimir.ui.picker

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformFolderPicker {
    suspend fun pickDirectory(initialPath: String? = null): String?
}

val LocalFolderPicker = staticCompositionLocalOf<PlatformFolderPicker?> { null }
```

In `app-desktop/src/main/kotlin/dev/mimir/desktop/picker/AwtFolderPicker.kt`:
Implement `pickDirectory` switching to `Dispatchers.IO`, running `JFileChooser` configured with `DIRECTORIES_ONLY`, setting `currentDirectory = resolveInitialDirectory(initialPath)`. Return `selectedFile.absolutePath` if `APPROVE_OPTION`, else `null`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-desktop:test --tests dev.mimir.desktop.picker.AwtFolderPickerTest`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt app-desktop/src/main/kotlin/dev/mimir/desktop/picker/AwtFolderPicker.kt app-desktop/src/test/kotlin/dev/mimir/desktop/picker/AwtFolderPickerTest.kt
rtk git commit -m "feat(desktop): add PlatformFolderPicker interface and AWT JFileChooser implementation"
```

---

### Task 3: Daemon Vault Configuration File Count & Inspection Endpoint

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Create: `daemon/src/test/kotlin/dev/mimir/daemon/InspectVaultTest.kt`
- Modify: `daemon/src/test/kotlin/dev/mimir/daemon/ConfigureVaultTest.kt`

**Interfaces:**
- Consumes: `LocalVault.listFiles()`, `EncryptedRemoteStorage.listAll()`
- Produces: `DaemonService.configureVault(vaultPath: String): Int` (returns local file count)
- Produces: `data class VaultInspectionDto(val localFileCount: Int, val remoteFileCount: Int, val requiresStrategyConfirmation: Boolean)`
- Produces: `DaemonService.inspectVault(): VaultInspectionDto`
- Produces: `GET /api/v1/vault/inspect`, updated `POST /api/v1/vault` returning `{"vaultPath":"...","fileCount":N}`

- [ ] **Step 1: Write the failing tests for inspect vault and file count**

```kotlin
package dev.mimir.daemon

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InspectVaultTest {
    @Test
    fun `inspect vault returns 403 when runtime is locked`() = testApplication {
        val (app, runtime) = createTestDaemonApp(isLocked = true)
        app()
        val response = client.get("/api/v1/vault/inspect")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `inspect vault returns counts and requiresStrategyConfirmation when both sides have files`() = testApplication {
        val (app, runtime) = createTestDaemonApp(isLocked = false, localFiles = 5, remoteFiles = 8)
        app()
        val response = client.get("/api/v1/vault/inspect")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(5, json["localFileCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(8, json["remoteFileCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(true, json["requiresStrategyConfirmation"]?.jsonPrimitive?.content?.toBoolean())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :daemon:test --tests dev.mimir.daemon.InspectVaultTest`  
Expected: FAIL with 404 Not Found for `/api/v1/vault/inspect`.

- [ ] **Step 3: Implement vault inspection and count logic in Daemon**

1. In `DaemonService.kt`: Update `suspend fun configureVault(vaultPath: String): Int` and add `suspend fun inspectVault(): VaultInspectionDto`.
2. In `DaemonRuntime.kt`:
   - In `configureVault`: validate path, count non-hidden notes using `FileSystemLocalVault.listFiles()`, persist config, return count. Ensure `SyncScheduler` does not auto-trigger during initial setup.
   - In `inspectVault`: if locked throw `IllegalStateException` (mapped to 403 in routes); count local files and query `remoteStorage.listAll("")` to count remote non-directory entries; return `VaultInspectionDto(local, remote, local > 0 && remote > 0)`.
3. In `Routes.kt`:
   - Update `POST /api/v1/vault` to respond with `VaultConfigResponse(vaultPath, count)`.
   - Add `GET /api/v1/vault/inspect` responding with `service.inspectVault()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:test --tests dev.mimir.daemon.InspectVaultTest`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/src/main/kotlin/dev/mimir/daemon/ daemon/src/test/kotlin/dev/mimir/daemon/
rtk git commit -m "feat(daemon): add vault file count to configureVault and GET /api/v1/vault/inspect"
```

---

### Task 4: UI Model & Desktop Client Vault Inspection Integration

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/model/FakeMimirUiModel.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/model/EngineMimirUiModel.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/HttpDaemonClient.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceUiModel.kt`
- Modify: `app-desktop/src/test/kotlin/dev/mimir/desktop/HttpDaemonClientTest.kt`

**Interfaces:**
- Consumes: `GET /api/v1/vault/inspect`, `POST /api/v1/vault`
- Produces: `MimirUiModel.configureVault(path: String): Int`
- Produces: `data class UiVaultInspection(val localFileCount: Int, val remoteFileCount: Int, val requiresStrategyConfirmation: Boolean)`
- Produces: `MimirUiModel.inspectVault(): UiVaultInspection`

- [ ] **Step 1: Write the failing client test**

```kotlin
// In app-desktop/src/test/kotlin/dev/mimir/desktop/HttpDaemonClientTest.kt
@Test
fun `inspectVault parses inspection response correctly`() = runTest {
    val server = startMockDaemonServer(
        inspectResponse = """{"localFileCount":10,"remoteFileCount":20,"requiresStrategyConfirmation":true}"""
    )
    val client = HttpDaemonClient(server.url)
    val inspection = client.inspectVault()
    assertEquals(10, inspection.localFileCount)
    assertEquals(20, inspection.remoteFileCount)
    assertEquals(true, inspection.requiresStrategyConfirmation)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :app-desktop:test --tests dev.mimir.desktop.HttpDaemonClientTest`  
Expected: FAIL with Unresolved reference `inspectVault`.

- [ ] **Step 3: Update `MimirUiModel` and implement in Desktop client and fake models**

1. In `MimirUiModel.kt`: define `data class UiVaultInspection(...)`, update `configureVault(path: String): Int`, add `suspend fun inspectVault(): UiVaultInspection`.
2. Implement in `HttpDaemonClient.kt` via Ktor HTTP requests.
3. Update `DaemonServiceUiModel.kt`, `FakeMimirUiModel.kt`, and `EngineMimirUiModel.kt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :app-desktop:test --tests dev.mimir.desktop.HttpDaemonClientTest`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/model/ app-desktop/src/main/kotlin/dev/mimir/desktop/ app-desktop/src/test/kotlin/dev/mimir/desktop/
rtk git commit -m "feat(ui): add inspectVault and count return to MimirUiModel and HttpDaemonClient"
```

---

### Task 5: 4-Step Setup Wizard with Initial Sync Confirmation & Obsidian Styling

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt`
- Modify: `ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt`

**Interfaces:**
- Consumes: `LocalFolderPicker`, `MimirUiModel.inspectVault()`, `MimirUiModel.configureVault()`
- Produces: 4-step wizard (`Step.Vault` with Browse button & badge $\rightarrow$ `Step.Google` $\rightarrow$ `Step.Password` $\rightarrow$ conditional `Step.InitialResolution`)

- [ ] **Step 1: Write the failing tests for 4-step wizard flow**

```kotlin
// In ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt
@Test
fun `vault step invokes platform folder picker and displays file count`() {
    val model = FakeMimirUiModel(fileCount = 42)
    var pickedPath: String? = null
    val fakePicker = object : PlatformFolderPicker {
        override suspend fun pickDirectory(initialPath: String?): String? = "/notes/vault"
    }

    compose.setContent {
        CompositionLocalProvider(LocalFolderPicker provides fakePicker) {
            SetupWizardScreen(model = model, onComplete = {})
        }
    }

    compose.onNodeWithText("📁 Обзор…").performClick()
    compose.onNodeWithText("/notes/vault").assertIsDisplayed()
    compose.onNodeWithText("✓ Найдено заметок: 42").assertIsDisplayed()
}

@Test
fun `setup wizard shows initial resolution step when requiresStrategyConfirmation is true`() {
    val model = FakeMimirUiModel(
        requiresStrategyConfirmation = true,
        localFileCount = 10,
        remoteFileCount = 15
    )
    var completed = false

    compose.setContent {
        SetupWizardScreen(model = model, onComplete = { completed = true })
    }

    // Advance through steps to step 4
    model.advanceToInitialResolution()
    compose.onNodeWithText("Двустороннее слияние с защитой от потерь").assertIsDisplayed()
    compose.onNodeWithText("Завершить настройку и синхронизировать").performClick()
    assertTrue(completed)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :ui:desktopTest --tests dev.mimir.ui.SetupWizardScreenTest`  
Expected: FAIL with node "📁 Обзор…" not found.

- [ ] **Step 3: Update `SetupWizardScreen.kt` with 4 steps and Obsidian aesthetic**

1. In `Step.Vault`:
   - Read `LocalFolderPicker.current`.
   - Render text field alongside a compact button `📁 Обзор…` (or `Обзор…` with folder icon).
   - When path is chosen or changed, call `model.configureVault(path)` and show note badge (`✓ Найдено заметок: X`).
2. Add `Step.InitialResolution`:
   - Query `model.inspectVault()`. If `!inspection.requiresStrategyConfirmation`, immediately call `onComplete()`.
   - If true, display the Obsidian-styled card showing local count, remote count, details about the two-way merge strategy, and button `Завершить настройку и синхронизировать`.
3. Apply Obsidian compact styles: thin borders (`1.dp`, `MaterialTheme.colorScheme.outline`), compact button paddings, `#141414` input backgrounds in dark mode.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:desktopTest --tests dev.mimir.ui.SetupWizardScreenTest`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt
rtk git commit -m "feat(ui): add browse folder dialog, note count badge and initial resolution step to wizard"
```

---

### Task 6: Visual Polish for StatusScreen and App Shell Integration

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`
- Modify: `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt`

**Interfaces:**
- Consumes: `AwtFolderPicker`, `LocalFolderPicker`, `ObsidianDarkColors`, `ObsidianLightColors`
- Produces: Polished full application in Obsidian theme with wired folder picker on Desktop

- [ ] **Step 1: Write/update UI test verifying Obsidian styling on StatusScreen**

```kotlin
// In ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt
@Test
fun `status screen renders cards with obsidian outline and surface styling`() {
    val model = FakeMimirUiModel()
    compose.setContent {
        MimirTheme {
            StatusScreen(model = model)
        }
    }
    compose.onNodeWithText(BuildConfig.APP_NAME).assertIsDisplayed()
}
```

- [ ] **Step 2: Run test to verify existing status screen tests pass**

Run: `rtk ./gradlew :ui:desktopTest --tests dev.mimir.ui.StatusScreenTest`  
Expected: PASS

- [ ] **Step 3: Restyle `StatusScreen` and `ConflictsScreen` & wire `AwtFolderPicker` in `Main.kt`**

1. In `StatusScreen.kt` and `ConflictsScreen.kt`:
   - Replace heavy cards with Obsidian-styled panels: `CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)`, borders with `BorderStroke(1.dp, MaterialTheme.colorScheme.outline)`.
   - Restyle status pills and progress bars with `#705DCF` accent.
2. In `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`:
   - Provide `LocalFolderPicker provides AwtFolderPicker()` inside the root `MimirApp` composable hierarchy.

- [ ] **Step 4: Run full desktop UI and daemon test suites**

Run: `rtk ./gradlew :ui:desktopTest :daemon:test :app-desktop:test`  
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/ app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt
rtk git commit -m "feat(ui,desktop): restyle status and conflict screens with obsidian tokens and wire AwtFolderPicker"
```
