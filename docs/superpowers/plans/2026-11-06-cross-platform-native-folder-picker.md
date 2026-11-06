# Нативные диалоги выбора папки и автообнаружение хранилищ Obsidian — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Обеспечить вызов настоящего системного проводника ОС на Windows (Common Item Dialog через PowerShell `-Sta`), macOS (Finder `NSOpenPanel`), Linux (XDG Desktop Portal → `zenity`/`kdialog` → Swing System L&F) и добавить автообнаружение существующих хранилищ Obsidian на Desktop из системного `obsidian.json`.

**Architecture:** В `:apps:desktop` вводится `sealed interface PickOutcome` (`Selected`, `Cancelled`, `Unavailable`) и компоновщик `FallbackFolderPicker` с `Mutex`, исключающий открытие запасных диалогов при отмене пользователем (`Cancel`) и повторное открытие при двойном клике. Внешние CLI-утилиты (`powershell.exe`, `zenity`, `kdialog`) вызываются через `ProcessExecutor`; `MacFolderPicker` и `AwtFolderPicker` получают ссылку на родительский `Frame` окна Mimir; `DesktopObsidianVaultDiscovery` читает системный `obsidian.json` и подключается через `LocalVaultDiscovery`.

**Tech Stack:** Kotlin Multiplatform 2.4.21 (JVM 26, WasmJs, Android), Compose Multiplatform 1.12, kotlinx-coroutines, kotlinx-serialization-json, dbus-java, AWT/Swing.

**Spec:** `docs/superpowers/specs/2026-11-04-cross-platform-native-folder-picker-design.md`

## Global Constraints

- Все терминальные команды выполняются строго с префиксом `rtk`; поиск файлов и текста — строго через `rtk fd` и `rtk rg`.
- Сборка без Android SDK и без секретов (`mise.local.toml`) обязана проходить; модуль `:libs:ui` обязан компилироваться под `desktop` и `wasmJs` (`./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs`).
- В `:libs:ui/commonMain` запрещены JVM-зависимости (`java.io.File`, `System.getProperty`, `ProcessBuilder`, `java.awt.*`).
- Единый заголовок всех десктопных диалогов выбора папки: `FOLDER_PICKER_TITLE = "Выберите папку хранилища"`.
- При тестировании корутин не использовать бесконечный `advanceUntilIdle()` на тикерах; при тестировании ОС-маршрутизации не мутировать глобальный `System.getProperties()` (передавать `osName` параметром).

## Инварианты Mimir

1. **Платформы:**
   - **Windows:** `WindowsFolderPicker` (PowerShell `-NoProfile -NonInteractive -Sta -WindowStyle Hidden -EncodedCommand` + `PlatformFocus.allowForegroundTransfer()`) → фоллбэк `AwtFolderPicker` (System L&F); `DesktopObsidianVaultDiscovery` читает `%APPDATA%\obsidian\obsidian.json`.
   - **macOS:** `MacFolderPicker` (`java.awt.FileDialog` с `apple.awt.fileDialogForDirectories = "true"` и родительским `Frame`) → фоллбэк `AwtFolderPicker`; `DesktopObsidianVaultDiscovery` читает `~/Library/Application Support/obsidian/obsidian.json`.
   - **Linux:** `PortalFolderPicker` (D-Bus XDG Portal) → `ZenityFolderPicker` (`zenity`, затем `kdialog`) → `AwtFolderPicker`; `DesktopObsidianVaultDiscovery` читает `$XDG_CONFIG_HOME/obsidian/obsidian.json`, `~/.config/obsidian/obsidian.json` и Flatpak `~/.var/app/md.obsidian.Obsidian/config/obsidian/obsidian.json`.
   - **Android:** без изменений (`AndroidFolderPicker` через SAF `OpenDocumentTree` и `FileSystemVaultDiscovery`).
   - **Docker / Web UI / Browser Extension / Obsidian Plugin:** неприменимо (`LocalFolderPicker == null`, `LocalVaultDiscovery == null`).
2. **Дизайн:**
   - Порты `PlatformFolderPicker` (+ `FakeFolderPicker`) и `VaultDiscovery` в `:libs:ui`; адаптеры в `:apps:desktop`.
   - `sealed interface PickOutcome` + компоновщик `FallbackFolderPicker` (Chain of Responsibility + Single-Flight `Mutex`).
   - Побочные эффекты (`ProcessExecutor`, `osName`, `parentFrame`, `allowForeground`) передаются параметрами со значениями по умолчанию по образцу `SystemBrowserOpener`.
3. **IO:**
   - Все вызовы процессов, D-Bus, `EventQueue.invokeAndWait` и чтение `obsidian.json` выполняются на `Dispatchers.IO`.
   - 0 вызовов Google Drive API, 0 байт сетевого трафика; при отмене корутины дочерний процесс уничтожается через `process.destroyForcibly()`.
4. **UX:**
   - Кнопка «Обзор…» в `VaultStep` блокируется (`picking = true`) на всё время работы системного диалога.
   - Имена обнаруженных хранилищ в `VaultStep` корректно извлекаются как для путей с `/`, так и для путей Windows с `\`.
   - Нажатие `Cancel` / `Esc` в любом системном диалоге возвращает `PickOutcome.Cancelled` (`null`) без открытия запасных диалогов.

## Review Focus

- Отмена пользователем в `zenity` или `kdialog` (`exitCode == 1`) обязана возвращать `PickOutcome.Cancelled` и останавливать цепочку, не вызывая следующую утилиту и `AwtFolderPicker`.
- Отмена корутины (`CancellationException`) внутри `FallbackFolderPicker`, `MacFolderPicker`, `PortalFolderPicker` и `ProcessExecutor` обязана пробрасываться вверх, а не маскироваться под `PickOutcome.Unavailable`.
- Передача `initialPath` в `WindowsFolderPicker` должна идти через переменную окружения процесса `MIMIR_INITIAL_PATH`, а сам скрипт — через `-EncodedCommand` (`Charsets.UTF_16LE` Base64), чтобы кириллица и одинарные кавычки в путях (`C:\Users\Илья\O'Neil Vault`) не ломали парсер PowerShell.
- При передаче несуществующего или пустого `initialPath` в `ZenityFolderPicker` флаг `--filename` не должен добавляться (иначе `zenity` с `--filename=/` открывает корень `/` вместо домашней папки).
- Быстрый повторный клик по кнопке «Обзор…» или параллельный вызов `FallbackFolderPicker.pickDirectory` не должен открывать второе окно проводника (`Mutex.tryLock()`).

---

### Task 1: `FakeFolderPicker`, кроссплатформенный `vaultDisplayName` и блокировка повторного клика в `VaultStep` (`:libs:ui`)

**Files:**
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt`
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/VaultStep.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt`

**Interfaces:**
- Produces:
  - `class FakeFolderPicker(var nextResult: String? = null, private val onPick: (suspend (String?) -> String?)? = null) : PlatformFolderPicker` (с полями `lastInitialPath: String?` и `callCount: Int`) в пакете `dev.mimir.ui.picker`
  - `internal fun vaultDisplayName(vaultPath: String): String` в `dev.mimir.ui.screens.setup`

- [ ] **Step 1: Написать падающие тесты в `SetupWizardScreenTest.kt` на `FakeFolderPicker`, Windows-пути в обнаруженных хранилищах и блокировку кнопки при открытом диалоге**

В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt` заменить анонимные `object : PlatformFolderPicker` на `FakeFolderPicker` и добавить тесты:
```kotlin
    @Test
    fun `vaultDisplayName extracts folder name from both unix and windows paths`() {
        assertEquals("Notes", dev.mimir.ui.screens.setup.vaultDisplayName("/home/user/Notes/"))
        assertEquals("My Vault", dev.mimir.ui.screens.setup.vaultDisplayName("C:\\Users\\Alex\\Documents\\My Vault\\"))
        assertEquals("C:", dev.mimir.ui.screens.setup.vaultDisplayName("C:\\"))
    }

    @Test
    fun `browse button is disabled while folder picker dialog is open`() = runComposeUiTest {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        val picker = dev.mimir.ui.picker.FakeFolderPicker(onPick = { gate.await() })
        setContent {
            CompositionLocalProvider(LocalFolderPicker provides picker) {
                SetupWizardScreen(FakeMimirUiModel(fileCount = 7), onComplete = {})
            }
        }

        onNodeWithText("Обзор…").performClick()
        waitForIdle()
        onNodeWithText("Обзор…").performClick()
        assertEquals(1, picker.callCount)

        gate.complete("/notes/vault")
        waitUntilExactlyOneExists(hasText("✓ Найдено заметок: 7"))
    }
```

- [ ] **Step 2: Запустить тест `:libs:ui:desktopTest` и убедиться, что он не компилируется**

Run: `rtk ./gradlew :libs:ui:desktopTest`
Expected: FAIL (`Unresolved reference 'FakeFolderPicker'`, `Unresolved reference 'vaultDisplayName'`).

- [ ] **Step 3: Реализовать `FakeFolderPicker` в `PlatformFolderPicker.kt`, `vaultDisplayName` и флаг `picking` в `VaultStep.kt`, обновить `UiScreenshotTest.kt`**

1. В `libs/ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt` добавить `FakeFolderPicker`:
```kotlin
class FakeFolderPicker(
    var nextResult: String? = null,
    private val onPick: (suspend (String?) -> String?)? = null,
) : PlatformFolderPicker {
    var lastInitialPath: String? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun pickDirectory(initialPath: String?): String? {
        callCount++
        lastInitialPath = initialPath
        return if (onPick != null) onPick.invoke(initialPath) else nextResult
    }
}
```
2. В `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/VaultStep.kt`:
   - Добавить `internal fun vaultDisplayName(vaultPath: String): String = vaultPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { vaultPath }` и использовать её вместо `vault.trimEnd('/').substringAfterLast('/').ifEmpty { vault }`.
   - Добавить `var picking by remember { mutableStateOf(false) }`, выставлять `picking = true` перед `picker.pickDirectory(...)` и сбрасывать `picking = false` в `finally`; у кнопки «Обзор…» выставить `enabled = !busy && !picking`.
3. В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt` заменить `dummyPicker = object : PlatformFolderPicker ...` на `private val dummyPicker = FakeFolderPicker()`.

- [ ] **Step 4: Проверить прохождение тестов и компиляции WasmJs в `:libs:ui`**

Run: `rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Зафиксировать изменения коммитом**

```bash
rtk git add libs/ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt \
  libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/VaultStep.kt \
  libs/ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt \
  libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt && \
rtk git commit -m "feat(ui): add FakeFolderPicker, single-flight browse lock and Windows path display name"
```

---

### Task 2: `PickOutcome`, `FallbackFolderPicker`, `ProcessExecutor` и перевод `PortalFolderPicker` / `AwtFolderPicker` на контракт кандидатов (`:apps:desktop`)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/FallbackFolderPicker.kt`
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ProcessExecutor.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/AwtFolderPicker.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/FallbackFolderPickerTest.kt`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/AwtFolderPickerTest.kt`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/PortalFolderPickerTest.kt`

**Interfaces:**
- Produces:
  - `internal const val FOLDER_PICKER_TITLE = "Выберите папку хранилища"`
  - `sealed interface PickOutcome` (`Selected(val path: String)`, `Cancelled`, `Unavailable(val reason: String, val cause: Throwable? = null)`)
  - `fun interface FolderPickerCandidate : PlatformFolderPicker` (`suspend fun tryPick(initialPath: String?): PickOutcome`)
  - `class FallbackFolderPicker(internal val candidates: List<FolderPickerCandidate>) : PlatformFolderPicker`
  - `internal fun resolveExistingDirectory(initialPath: String?): File?`
  - `data class ProcessResult(val exitCode: Int, val stdout: String)`
  - `fun interface ProcessExecutor` (`suspend fun execute(command: List<String>, env: Map<String, String>): ProcessResult`) и `internal suspend fun runSystemProcess(command: List<String>, env: Map<String, String> = emptyMap()): ProcessResult`

- [ ] **Step 1: Написать падающие тесты в `FallbackFolderPickerTest.kt` и обновить `AwtFolderPickerTest.kt` / `PortalFolderPickerTest.kt`**

Создать `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/FallbackFolderPickerTest.kt`:
```kotlin
package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackFolderPickerTest {
    @Test
    fun `returns path from the first candidate that selects a folder`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Selected("/vault/notes") },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertEquals("/vault/notes", picker.pickDirectory("/init"))
        assertTrue(!secondCalled)
    }

    @Test
    fun `stops chain and returns null when user cancels dialog`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Cancelled },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertNull(picker.pickDirectory(null))
        assertTrue(!secondCalled)
    }

    @Test
    fun `falls back to next candidate on Unavailable or thrown exception`() = runTest {
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Unavailable("no portal") },
            FolderPickerCandidate { error("boom") },
            FolderPickerCandidate { PickOutcome.Selected("/from-third") },
        )
        assertEquals("/from-third", picker.pickDirectory(null))
    }

    @Test
    fun `rethrows CancellationException without calling fallback`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { throw CancellationException("cancelled") },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertFailsWith<CancellationException> { picker.pickDirectory(null) }
        assertTrue(!secondCalled)
    }

    @Test
    fun `single-flight mutex rejects concurrent pickDirectory call`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val picker = FallbackFolderPicker(
            FolderPickerCandidate {
                entered.complete(Unit)
                release.await()
                PickOutcome.Selected("/first")
            },
        )
        val first = async { picker.pickDirectory(null) }
        entered.await()
        assertNull(picker.pickDirectory(null))
        release.complete(Unit)
        assertEquals("/first", first.await())
    }

    @Test
    fun `resolveExistingDirectory resolves directory or existing parent`() {
        assertNull(resolveExistingDirectory(null))
        assertNull(resolveExistingDirectory("   "))
        val tmp = File(System.getProperty("java.io.tmpdir"))
        assertEquals(tmp, resolveExistingDirectory(tmp.absolutePath))
        val childFile = File(tmp, "nonexistent-child-${System.nanoTime()}.md")
        assertEquals(tmp, resolveExistingDirectory(childFile.absolutePath))
    }
}
```
В `PortalFolderPickerTest.kt` добавить проверку `outcomeFromResponse`:
- `code == 0` с `file:///home/me/My%20Notes` → `PickOutcome.Selected(File("/home/me/My Notes").absolutePath)`
- `code == 1` → `PickOutcome.Cancelled`
- `code == 0` с `smb://server/share` → `PickOutcome.Unavailable`

- [ ] **Step 2: Запустить `:apps:desktop:test` и убедиться, что тест падает**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.picker.*"`
Expected: FAIL (`Unresolved reference 'FallbackFolderPicker'`, `Unresolved reference 'PickOutcome'`).

- [ ] **Step 3: Реализовать `FallbackFolderPicker.kt`, `ProcessExecutor.kt` и обновить `AwtFolderPicker.kt` и `PortalFolderPicker.kt`**

1. Создать `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/FallbackFolderPicker.kt` со структурами `FOLDER_PICKER_TITLE`, `PickOutcome`, `FolderPickerCandidate`, `FallbackFolderPicker` и `resolveExistingDirectory(initialPath: String?): File?` согласно разделу 3.2 спеки.
2. Создать `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ProcessExecutor.kt`:
   - `data class ProcessResult(val exitCode: Int, val stdout: String)`
   - `fun interface ProcessExecutor { suspend fun execute(command: List<String>, env: Map<String, String>): ProcessResult }`
   - `internal suspend fun runSystemProcess(command: List<String>, env: Map<String, String> = emptyMap()): ProcessResult = withContext(Dispatchers.IO) { ... }` с `redirectError(ProcessBuilder.Redirect.DISCARD)` и уничтожением процесса `process.destroyForcibly()` при отмене корутины (`runInterruptible` или `suspendCancellableCoroutine`).
3. Обновить `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/AwtFolderPicker.kt`:
   - `class AwtFolderPicker(private val parentFrame: () -> Frame? = { null }) : FolderPickerCandidate`
   - В `EventQueue.invokeAndWait` вызывать `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())`, `dialogTitle = FOLDER_PICKER_TITLE`, `showOpenDialog(parentFrame())`, возвращать `PickOutcome.Selected` / `PickOutcome.Cancelled`.
   - В `resolveInitialDirectory(initialPath: String?)` использовать `resolveExistingDirectory(initialPath) ?: File(System.getProperty("user.home"))`.
4. Обновить `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt`:
   - `class PortalFolderPicker : FolderPickerCandidate`
   - Метод `tryPick(initialPath: String?): PickOutcome` возвращает `PickOutcome.Unavailable` при ошибке D-Bus и `outcomeFromResponse(signal.response.toInt(), uris)` при ответе портала (`code != 0` или пустой список → `PickOutcome.Cancelled`, не-`file` URI → `PickOutcome.Unavailable`, валидный `file` URI → `PickOutcome.Selected`).

- [ ] **Step 4: Запустить тесты `:apps:desktop:test` и убедиться, что они проходят**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.picker.*"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Зафиксировать изменения коммитом**

```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/ && \
rtk git commit -m "refactor(desktop): introduce PickOutcome, FallbackFolderPicker and ProcessExecutor"
```

---

### Task 3: Нативные адаптеры `WindowsFolderPicker`, `MacFolderPicker`, `ZenityFolderPicker` и фабрика `defaultFolderPicker()` (`:apps:desktop`)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/WindowsFolderPicker.kt`
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/MacFolderPicker.kt`
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ZenityFolderPicker.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/FallbackFolderPicker.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/WindowsFolderPickerTest.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/ZenityFolderPickerTest.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/MacFolderPickerTest.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/DefaultFolderPickerRoutingTest.kt`

**Interfaces:**
- Consumes: `FolderPickerCandidate`, `PickOutcome`, `FallbackFolderPicker`, `ProcessExecutor`, `ProcessResult`, `FOLDER_PICKER_TITLE`, `resolveExistingDirectory`, `PlatformFocus.allowForegroundTransfer()`
- Produces:
  - `class WindowsFolderPicker(private val executor: ProcessExecutor = ProcessExecutor(::runSystemProcess), private val allowForeground: () -> Unit = PlatformFocus::allowForegroundTransfer) : FolderPickerCandidate`
  - `class MacFolderPicker(private val parentFrame: () -> Frame? = { null }) : FolderPickerCandidate`
  - `class ZenityFolderPicker(private val executor: ProcessExecutor = ProcessExecutor(::runSystemProcess)) : FolderPickerCandidate`
  - `fun defaultFolderPicker(osName: String = System.getProperty("os.name").orEmpty(), executor: ProcessExecutor = ProcessExecutor(::runSystemProcess), parentFrame: () -> Frame? = { null }): PlatformFolderPicker`

- [ ] **Step 1: Написать падающие модульные тесты `WindowsFolderPickerTest`, `ZenityFolderPickerTest`, `MacFolderPickerTest` и `DefaultFolderPickerRoutingTest`**

1. `WindowsFolderPickerTest.kt`:
   - Проверяет, что `command` начинается с `["powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-WindowStyle", "Hidden", "-EncodedCommand"]`, а декодированный из Base64 (`Charsets.UTF_16LE`) скрипт содержит `AutoUpgradeEnabled = $true`, `FOLDER_PICKER_TITLE` и `$env:MIMIR_INITIAL_PATH`.
   - Проверяет вызов `allowForeground()` и передачу существующей папки в `env["MIMIR_INITIAL_PATH"]`.
   - Проверяет `exitCode = 0, stdout = "C:\\Users\\Илья\\O'Neil Vault\r\n"` → `PickOutcome.Selected("C:\\Users\\Илья\\O'Neil Vault")`.
   - Проверяет `exitCode = 0, stdout = ""` → `PickOutcome.Cancelled`.
   - Проверяет `exitCode = 1` и `IOException` → `PickOutcome.Unavailable`.
   - Проверяет проброс `CancellationException`.
2. `ZenityFolderPickerTest.kt`:
   - `zenity` возвращает `exitCode = 0, stdout = "/home/user/Vault\n"` → `PickOutcome.Selected("/home/user/Vault")`, `kdialog` не вызывается.
   - `zenity` возвращает `exitCode = 1, stdout = ""` (нажат `Cancel`) → `PickOutcome.Cancelled`, `kdialog` **не** вызывается.
   - `zenity` бросает `IOException` (не установлен) или возвращает `exitCode = 255` (нет дисплея/ошибка GTK) → вызывается `kdialog`.
   - `kdialog` возвращает `exitCode = 1` → `PickOutcome.Cancelled`.
   - Обе утилиты недоступны → `PickOutcome.Unavailable`.
   - При `initialPath = null` или несуществующем пути флаг `--filename` в команду `zenity` не добавляется; при существующей директории добавляется `--filename=<absPath>/`.
3. `MacFolderPickerTest.kt`:
   - Пробрасывает `CancellationException`.
   - При выбросе исключения внутри `dialogRunner` возвращает `PickOutcome.Unavailable` и гарантированно восстанавливает прежнее значение `System.getProperty("apple.awt.fileDialogForDirectories")`.
4. `DefaultFolderPickerRoutingTest.kt`:
   - Проверяет типы кандидатов внутри `FallbackFolderPicker` для `osName = "Windows 11"` (`[WindowsFolderPicker, AwtFolderPicker]`), `"Mac OS X"` (`[MacFolderPicker, AwtFolderPicker]`), `"Linux"` (`[PortalFolderPicker, ZenityFolderPicker, AwtFolderPicker]`), `"FreeBSD"` (`AwtFolderPicker`).

- [ ] **Step 2: Запустить тесты `:apps:desktop:test` и убедиться, что они не компилируются**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.picker.*"`
Expected: FAIL (`Unresolved reference 'WindowsFolderPicker'`, `'ZenityFolderPicker'`, `'MacFolderPicker'`).

- [ ] **Step 3: Реализовать `WindowsFolderPicker.kt`, `ZenityFolderPicker.kt`, `MacFolderPicker.kt` и `defaultFolderPicker()`**

1. Реализовать `WindowsFolderPicker.kt` согласно разделу 3.4 спеки (скрипт в `Base64.getEncoder().encodeToString(POWERSHELL_SCRIPT.toByteArray(Charsets.UTF_16LE))`, вызов `allowForeground()`, передача `MIMIR_INITIAL_PATH` в `env`).
2. Реализовать `ZenityFolderPicker.kt` согласно разделу 3.5 спеки.
3. Реализовать `MacFolderPicker.kt` согласно разделу 3.6 спеки (с параметром `private val openDialog: (Frame?, File?) -> String?` со значением по умолчанию через `FileDialog` для чистой проверки восстановления свойства `apple.awt.fileDialogForDirectories` в headless-тестах).
4. Перенести и обновить `defaultFolderPicker(osName, executor, parentFrame)` в `FallbackFolderPicker.kt` согласно разделу 3.7 спеки (удалив старую функцию из `PortalFolderPicker.kt`).

- [ ] **Step 4: Запустить тесты `:apps:desktop:test` и убедиться, что все тесты проходят**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.picker.*"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Зафиксировать изменения коммитом**

```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/ && \
rtk git commit -m "feat(desktop): add Windows, macOS and Zenity/KDialog native folder pickers"
```

---

### Task 4: Автообнаружение хранилищ `DesktopObsidianVaultDiscovery` и подключение в `Main.kt` (`:apps:desktop`)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscovery.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscoveryTest.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`

**Interfaces:**
- Consumes: `VaultDiscovery`, `LocalVaultDiscovery` из `:libs:ui`, `defaultFolderPicker` из `:apps:desktop`
- Produces:
  - `class DesktopObsidianVaultDiscovery(private val configFiles: () -> List<File> = ::defaultObsidianConfigFiles) : VaultDiscovery`
  - `internal fun defaultObsidianConfigFiles(osName: String = System.getProperty("os.name").orEmpty(), userHome: String = System.getProperty("user.home").orEmpty(), env: (String) -> String? = System::getenv): List<File>`

- [ ] **Step 1: Написать падающие тесты в `DesktopObsidianVaultDiscoveryTest.kt`**

Создать `apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscoveryTest.kt`:
```kotlin
package dev.mimir.desktop.picker

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopObsidianVaultDiscoveryTest {
    @Test
    fun `parses obsidian json sorting open and recent vaults first and filtering missing dirs`() = runTest {
        val tempRoot = Files.createTempDirectory("mimir-obsidian-test").toFile()
        try {
            val vaultOld = File(tempRoot, "OldVault").apply { mkdirs() }
            val vaultRecent = File(tempRoot, "RecentVault").apply { mkdirs() }
            val vaultOpen = File(tempRoot, "OpenVault").apply { mkdirs() }
            val missingPath = File(tempRoot, "DeletedVault").absolutePath

            val configFile = File(tempRoot, "obsidian.json")
            configFile.writeText(
                """
                {
                  "vaults": {
                    "1": { "path": ${quoteJson(vaultOld.absolutePath)}, "ts": 100 },
                    "2": { "path": ${quoteJson(missingPath)}, "ts": 999, "open": true },
                    "3": { "path": ${quoteJson(vaultRecent.absolutePath)}, "ts": 500 },
                    "4": { "path": ${quoteJson(vaultOpen.absolutePath)}, "ts": 200, "open": true }
                  }
                }
                """.trimIndent(),
            )

            val discovery = DesktopObsidianVaultDiscovery(configFiles = { listOf(configFile) })
            assertEquals(
                listOf(vaultOpen.absolutePath, vaultRecent.absolutePath, vaultOld.absolutePath),
                discovery.findVaults(),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `ignores malformed or missing obsidian json files without failing`() = runTest {
        val tempRoot = Files.createTempDirectory("mimir-obsidian-broken").toFile()
        try {
            val broken = File(tempRoot, "broken.json").apply { writeText("{not-json") }
            val missing = File(tempRoot, "missing.json")
            val discovery = DesktopObsidianVaultDiscovery(configFiles = { listOf(missing, broken) })
            assertTrue(discovery.findVaults().isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `defaultObsidianConfigFiles resolves platform-specific paths`() {
        val winFiles = defaultObsidianConfigFiles(
            osName = "Windows 11",
            userHome = "C:\\Users\\me",
            env = { if (it == "APPDATA") "C:\\Users\\me\\AppData\\Roaming" else null },
        )
        assertEquals(listOf(File("C:\\Users\\me\\AppData\\Roaming", "obsidian/obsidian.json")), winFiles)

        val macFiles = defaultObsidianConfigFiles(
            osName = "Mac OS X",
            userHome = "/Users/me",
            env = { null },
        )
        assertEquals(listOf(File("/Users/me/Library/Application Support/obsidian/obsidian.json")), macFiles)

        val linuxFiles = defaultObsidianConfigFiles(
            osName = "Linux",
            userHome = "/home/me",
            env = { if (it == "XDG_CONFIG_HOME") "/home/me/.xdg" else null },
        )
        assertEquals(
            listOf(
                File("/home/me/.xdg/obsidian/obsidian.json"),
                File("/home/me/.config/obsidian/obsidian.json"),
                File("/home/me/.var/app/md.obsidian.Obsidian/config/obsidian/obsidian.json"),
            ),
            linuxFiles,
        )
    }

    private fun quoteJson(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
```

- [ ] **Step 2: Запустить `:apps:desktop:test` и убедиться, что тест падает**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.picker.DesktopObsidianVaultDiscoveryTest"`
Expected: FAIL (`Unresolved reference 'DesktopObsidianVaultDiscovery'`).

- [ ] **Step 3: Реализовать `DesktopObsidianVaultDiscovery.kt` и подключить его вместе с `parentFrame = { window }` в `Main.kt`**

1. Создать `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscovery.kt`:
   - `@Serializable private data class ObsidianConfig(val vaults: Map<String, ObsidianVaultEntry> = emptyMap())`
   - `@Serializable private data class ObsidianVaultEntry(val path: String = "", val ts: Long = 0L, val open: Boolean = false)`
   - В `findVaults()` на `Dispatchers.IO` прочитать существующие файлы из `configFiles()`, распарсить через `Json { ignoreUnknownKeys = true }`, отсортировать `compareByDescending<ObsidianVaultEntry> { it.open }.thenByDescending { it.ts }`, оставить только существующие директории (`File(entry.path).isDirectory`), вернуть `distinct()`.
2. В `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`:
   - Перенести `val folderPicker = remember(window) { defaultFolderPicker(parentFrame = { window }) }` внутрь блока `Window(...) { ... }`.
   - Создать `val vaultDiscovery = remember { DesktopObsidianVaultDiscovery() }`.
   - Передать `LocalVaultDiscovery provides vaultDiscovery` в `CompositionLocalProvider`.

- [ ] **Step 4: Запустить полную проверку модулей и графа зависимостей**

Run: `rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs :apps:desktop:test buildHealth`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Проверить дрейф базы знаний `context-init` и зафиксировать коммит**

Run: `rtk context-init check --compact` (при наличии дрейфа выполнить `rtk context-init update . --compact`)
```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscovery.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscoveryTest.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt && \
rtk git commit -m "feat(desktop): add DesktopObsidianVaultDiscovery and bind parent window to folder picker"
```
