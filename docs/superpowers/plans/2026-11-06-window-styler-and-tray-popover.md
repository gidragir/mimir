# Нативная стилизация окна и кроссплатформенный трей-поповер — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Окрасить системную рамку главного окна в цвета темы Obsidian (`PlatformWindowStyler`: Win32 FFM `DwmSetWindowAttribute` на Windows, прозрачный `fullWindowContent` на macOS, нативный WM на Linux) при сохранении `undecorated = false` и заменить устаревшее меню трея на кроссплатформенный плавающий Compose-поповер (`TrayPopoverWindow` + `TrayPopoverScreen`) на Windows, Linux и macOS.

**Architecture:** В `:libs:ui/commonMain` добавляются векторные иконки (`MimirIcons.Refresh`, `ExternalLink`, `Power`, `Sparkles`) и чистый экран `TrayPopoverScreen`, работающий поверх единого контракта `MimirUiModel`, компонентов `MimirComponents` и текстов `SyncTexts`/`UpdateTexts`. В `:apps:desktop` создаются `PlatformWindowStyler` (стилизация `java.awt.Window` через JDK 27 FFM `dwmapi.dll` и свойства `JRootPane`), чистый калькулятор координат `ScreenPositionCalculator`, контроллер анти-дребезга фокуса `PopoverVisibilityController` и безрамочное окно `TrayPopoverWindow` (`Window.Type.POPUP`). Контракт `Tray` расширяется колбэком `onActivate: (TrayClickAnchor?) -> Unit`, включается поддержка macOS в `createTray()`, из `AwtTray` удаляется Win32 `PopupMenu`, а в `DbusStatusNotifierTray` сохраняется резервный `DbusMenu` для системных панелей Linux.

**Tech Stack:** Kotlin Multiplatform 2.4.21 (JVM 26, WasmJs, Android), Compose Multiplatform 1.12, JDK 27 Foreign Function & Memory API (`java.lang.foreign`), kotlinx-coroutines, dbus-java, AWT/Swing.

**Spec:** `docs/superpowers/specs/2026-11-04-custom-titlebar-and-tray-popover-design.md`

## Global Constraints

- Все терминальные команды выполняются строго с префиксом `rtk`; поиск файлов и текста — строго через `rtk fd` и `rtk rg`.
- Сборка без Android SDK и без секретов (`mise.local.toml`) обязана проходить; модуль `:libs:ui` обязан компилироваться под `desktop` и `wasmJs` (`rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs`).
- В `:libs:ui/commonMain` категорически запрещены JVM/AWT-зависимости (`java.awt.*`, `androidx.compose.ui.window.*`, `System.getProperty`).
- Запрещено использовать emoji в кнопках и индикаторах — только векторные иконки из `dev.mimir.ui.components.MimirIcons`.
- Запрещено хардкодить цвета тёмной темы (`MimirTokens.DarkSurface`) внутри `@Composable` экранов — использовать `MaterialTheme.colorScheme` (поддержка `Dark` и `Light` тем).
- Главное окно сохраняет `undecorated = false`; поповер трея обязан выставлять `window.type = java.awt.Window.Type.POPUP` до показа окна.

## Инварианты Mimir

1. **Платформы:**
   - **Linux:** главное окно с `undecorated = false` (без лишней внутренней шапки в niri/Sway); `DbusStatusNotifierTray` по левому клику (`Activate(x, y)`) переключает `TrayPopoverWindow` (`Window.Type.POPUP`), по правому клику отдаёт нативный `DbusMenu`.
   - **Windows:** главное окно с `undecorated = false`, окрашенное через `PlatformWindowStyler` (`dwmapi.dll` `DwmSetWindowAttribute` атрибуты 20, 34, 35, 36 в цвета `MimirTokens`); `AwtTray` без `java.awt.PopupMenu` открывает `TrayPopoverWindow` по одиночному клику и разворачивает главное окно через `PlatformFocus.activate` по двойному клику.
   - **macOS:** главное окно с `apple.awt.fullWindowContent = true`, `transparentTitleBar = true`, `windowTitleVisible = false` и `NSAppearanceNameDarkAqua` / `Aqua`; `AwtTray` включён в `createTray()` и открывает `TrayPopoverWindow` под менюбаром в правом верхнем углу.
   - **Android / Docker / Web UI / Browser Extension / Obsidian Plugin:** неприменимо (изолированы от десктопных окон, `:libs:ui/commonMain` остаётся чистым KMP-кодом).
2. **Дизайн:**
   - UDF: `TrayPopoverScreen` читает `StateFlow` из `MimirUiModel` без промежуточных ViewModel (эталон — `StatusScreen.kt`).
   - Переиспользование готовых компонентов `MimirCard`, `MimirStatusBadge`, `MimirProgressBar`, `MimirButton`, `SyncTexts`, `UpdateTexts` и `vaultDisplayName`.
   - Чистые тестируемые функции/классы для платформенной логики: `PlatformWindowStyler.toWin32ColorRef`, `ScreenPositionCalculator.calculate`, `PopoverVisibilityController`.
3. **IO:**
   - 0 вызовов Google Drive API, 0 байт дискового и сетевого трафика.
   - При `visible == false` содержимое `TrayPopoverWindow` и секундный тикер `produceState` не композируются (0% нагрузки на CPU/GPU в фоне).
4. **UX:**
   - `TrayPopoverScreen` поддерживает все 8 состояний: `Startup`, `StartupError`, `NotConfigured`/`Locked`, `Idle`, `Syncing` (`Scanning` и `Applying`), `Offline`, `Error`, `Conflicts`/`UpdateAvailable`.
   - Поповер закрывается при потере фокуса (`windowLostFocus`), нажатии `Esc` или повторном клике по иконке трея (с защитой от дребезга фокуса 200 мс).

## Review Focus

- Клик по иконке трея при уже открытом поповере сначала вызывает `windowLostFocus` (закрывая поповер), а через 10–50 мс доставляет `onActivate`: `PopoverVisibilityController` обязан подавлять повторное открытие в окне `0..<debounceMs` (200 мс), включая защиту от немонотонного сдвига системных часов (`elapsed < 0`).
- На многомониторных конфигурациях координаты второго монитора могут быть отрицательными (`bounds.x = -1920, bounds.y = -200`): `ScreenPositionCalculator` обязан считать рабочую область относительно `bounds.x` и `bounds.y`, а не от `(0, 0)`.
- Если размер экрана/рабочей области меньше размера поповера (или отступов), `coerceIn(minX, maxX)` в Kotlin бросит `IllegalArgumentException: Cannot coerce value to an empty range`: зажатие координат в `ScreenPositionCalculator` обязано использовать безопасный `coerceAtLeast` (`val safeMaxX = maxX.coerceAtLeast(minX)`).
- В `DbusStatusNotifierTray` панели Wayland без доступа к глобальным координатам передают `Activate(0, 0)`: координаты `(0, 0)` обязаны транслироваться в `anchor = null`, чтобы `ScreenPositionCalculator` использовал угол рабочей области по `insets`, а не левый верхний пиксель `(0, 0)`.
- В `TrayPopoverScreen` сырое сообщение `SyncStatus.Error.message` никогда не должно выводиться напрямую: заголовок ошибки формируется строго через `SyncTexts.phaseTitle(status, progress, needsSignIn)` / `SyncTexts.errorTitle(status.kind)`.

---

### Task 1: Векторные иконки `MimirIcons` и экран `TrayPopoverScreen` со всеми состояниями UX (`:libs:ui`)

**Files:**
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirIcons.kt`
- Create: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverScreen.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt`
- Create: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/TrayPopoverScreenTest.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt`

**Interfaces:**
- Consumes:
  - `MimirUiModel`, `FakeMimirUiModel`, `UpdateUiState`, `UpdateTexts` (`dev.mimir.ui.model`)
  - `MimirCard`, `MimirButton`, `MimirButtonVariant`, `MimirStatusBadge`, `MimirProgressBar` (`dev.mimir.ui.components`)
  - `SyncTexts` (`dev.mimir.ui.screens`), `vaultDisplayName` (`dev.mimir.ui.screens.setup`)
- Produces:
  - `MimirIcons.Refresh: ImageVector`, `MimirIcons.ExternalLink: ImageVector`, `MimirIcons.Power: ImageVector`, `MimirIcons.Sparkles: ImageVector` в `dev.mimir.ui.components.MimirIcons`
  - `@Composable fun TrayPopoverScreen(model: MimirUiModel?, startupError: String? = null, onOpenMainWindow: () -> Unit, onQuit: () -> Unit, clock: Clock = Clock.System, modifier: Modifier = Modifier)` в `dev.mimir.ui.screens`

- [ ] **Step 1: Написать падающие тесты на новые иконки и все 8 состояний `TrayPopoverScreen`**

В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt` добавить проверку иконок:
```kotlin
    @Test
    fun `popover icons have non-empty paths`() {
        listOf(
            MimirIcons.Refresh,
            MimirIcons.ExternalLink,
            MimirIcons.Power,
            MimirIcons.Sparkles,
        ).forEach { icon ->
            assertTrue(icon.root.size > 0, "Icon ${icon.name} should have vector nodes")
        }
    }
```

Создать `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/TrayPopoverScreenTest.kt`:
```kotlin
package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.model.UpdateKind
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.TrayPopoverScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class TrayPopoverScreenTest {
    private val fixedNow = Instant.fromEpochSeconds(1_700_000_600L)
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    @Test
    fun `startup state shows loading indicator and allows open and quit`() = runComposeUiTest {
        var opened = false
        var quit = false
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = null,
                    startupError = null,
                    onOpenMainWindow = { opened = true },
                    onQuit = { quit = true },
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Запуск…").assertIsDisplayed()
        onNodeWithTag("popover_open_button").performClick()
        assertTrue(opened)
        onNodeWithTag("popover_quit_button").performClick()
        assertTrue(quit)
    }

    @Test
    fun `startup error state displays error message`() = runComposeUiTest {
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = null,
                    startupError = "Порт занят",
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Не удалось запустить Mimir: Порт занят").assertIsDisplayed()
    }

    @Test
    fun `unconfigured or locked vault shows setup button and hides sync button`() = runComposeUiTest {
        var opened = false
        val model = FakeMimirUiModel(status = SyncStatus.NotConfigured, vaultPath = null, isLocked = true)
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = { opened = true },
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Настроить Mimir").assertIsDisplayed().performClick()
        assertTrue(opened)
        onNodeWithTag("popover_sync_button").assertDoesNotExist()
    }

    @Test
    fun `idle state displays vault name, path, last sync time and triggers sync`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            vaultPath = "/home/user/Documents/ObsidianVault",
            isLocked = false,
            lastSyncTime = fixedNow,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("ObsidianVault").assertIsDisplayed()
        onNodeWithText("/home/user/Documents/ObsidianVault").assertIsDisplayed()
        onNodeWithText("✓ Всё синхронизировано").assertIsDisplayed()
        onNodeWithTag("popover_sync_button").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, model.syncTriggers)
    }

    @Test
    fun `syncing applying state shows progress counter, current file and disables sync button`() = runComposeUiTest {
        val progress = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = fixedNow,
            done = 7,
            total = 20,
            current = SyncItem("notes/daily.md", SyncItemAction.Upload),
        )
        val model = FakeMimirUiModel(
            status = SyncStatus.Syncing,
            vaultPath = "/home/user/Notes",
            isLocked = false,
            progress = MutableStateFlow(progress),
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("7 из 20 файлов").assertIsDisplayed()
        onNodeWithText("⬆ Отправляю в Google Drive: notes/daily.md").assertIsDisplayed()
        onNodeWithTag("popover_sync_button").assertIsNotEnabled()
    }

    @Test
    fun `offline state shows offline hint and disables sync button`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Offline,
            vaultPath = "/home/user/Notes",
            isLocked = false,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Нет подключения к интернету").assertIsDisplayed()
        onNodeWithTag("popover_sync_button").assertIsNotEnabled()
    }

    @Test
    fun `error state shows canonical title without raw exception message`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Error("java.io.IOException: secret stacktrace", SyncErrorKind.SignInRequired),
            vaultPath = "/home/user/Notes",
            isLocked = false,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Войдите в Google снова").assertIsDisplayed()
        onNodeWithText("java.io.IOException: secret stacktrace").assertDoesNotExist()
    }

    @Test
    fun `conflicts and update banners are shown and open main window on click`() = runComposeUiTest {
        var openCalls = 0
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            vaultPath = "/home/user/Notes",
            isLocked = false,
            conflicts = listOf(UiConflictEntry("a.md", "a.conflict.md", fixedNow)),
            updateState = MutableStateFlow(
                UpdateUiState.Available(
                    version = "0.4.0",
                    notes = null,
                    kind = UpdateKind.DesktopInstaller,
                    canInstallInApp = true,
                    releaseUrl = "https://example.com",
                ),
            ),
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = { openCalls++ },
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithTag("popover_conflicts_banner").assertIsDisplayed().performClick()
        assertEquals(1, openCalls)
        onNodeWithTag("popover_update_banner").assertIsDisplayed().performClick()
        assertEquals(2, openCalls)
        assertFalse(model.status.value is SyncStatus.Error)
    }
}
```

- [ ] **Step 2: Запустить тесты `:libs:ui:desktopTest`, чтобы убедиться, что они падают**

Run: `rtk ./gradlew :libs:ui:desktopTest --tests "dev.mimir.ui.TrayPopoverScreenTest" --tests "dev.mimir.ui.MimirComponentsTest"`
Expected: FAIL (`Unresolved reference 'TrayPopoverScreen'`, `Unresolved reference 'Refresh'`)

- [ ] **Step 3: Реализовать иконки в `MimirIcons.kt` и экран `TrayPopoverScreen.kt`, добавить скриншот-тесты в `UiScreenshotTest.kt`**

В `libs/ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirIcons.kt` добавить 4 векторные иконки (`20.dp`, `24f×24f` viewport, `SolidColor(Color.White)`, `strokeLineWidth = 2f`, `StrokeCap.Round`, `StrokeJoin.Round`):
- `Refresh`: круговая стрелка обновления.
- `ExternalLink`: квадрат со стрелкой в правый верхний угол (`moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f)`, `moveTo(10f, 14f); lineTo(21f, 3f)` + контур окна).
- `Power`: полукруг питания + вертикальная черта `moveTo(12f, 2f); lineTo(12f, 12f)`.
- `Sparkles`: четырёхлучевая звезда обновления.

Создать `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverScreen.kt`:
- Ширина корневого `MimirCard` — `Modifier.width(320.dp).testTag("tray_popover_root")`, внутренний `Column` с `Modifier.padding(MimirTokens.Space16)` и `Arrangement.spacedBy(MimirTokens.Space12)`.
- При `model == null`:
  - Шапка с `Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)`.
  - Если `startupError != null` — текст `"Не удалось запустить Mimir: $startupError"` цветом `MimirTokens.StatusError`; иначе `MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth())` + `Text("Запуск…", style = MaterialTheme.typography.bodyMedium)`.
  - Кнопки `popover_open_button` («Открыть Mimir», `MimirButtonVariant.Secondary`) и `popover_quit_button` («Выход», `MimirButtonVariant.Ghost`).
- При `model != null`:
  - Подписка через `collectAsState()` на `status`, `progress`, `vaultPath`, `isLocked`, `lastSyncTime`, `accountEmail`, `conflicts`, `updateState`.
  - Шапка (`Row` `SpaceBetween`): `Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)` и `MimirStatusBadge(status = status, modifier = Modifier.testTag("popover_status_badge"))`.
  - Определение `val needsSetup = status == SyncStatus.NotConfigured || vaultPath == null || isLocked`.
  - Внутренний блок состояния (`Column` на подложке `MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)`, скругление `MimirTokens.Radius6`, `padding(MimirTokens.Space12)`, `spacedBy(MimirTokens.Space8)`):
    - Если `vaultPath != null`: `Row` с `Icon(MimirIcons.Folder, contentDescription = null, tint = MimirTokens.Accent, modifier = Modifier.size(16.dp))` и `Text(vaultDisplayName(vaultPath!!), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)`, ниже `Text(vaultPath!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)`.
    - Если `needsSetup`: текст `if (isLocked && vaultPath != null) "Хранилище заблокировано" else "Хранилище не подключено"`.
    - Иначе заголовок фазы `SyncTexts.phaseTitle(status, progress, needsSignIn = status is SyncStatus.Error && accountEmail == null)` (для `SyncStatus.Error` цвет `MimirTokens.StatusError`, без вывода `status.message`).
    - При `status == SyncStatus.Syncing`:
      - для `SyncPhase.Scanning` / `Comparing` / `null` — `MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth())` и `progress?.let { Text(SyncTexts.scanned(it.scanned, it.foldersScanned), style = MaterialTheme.typography.bodySmall) }`;
      - для `SyncPhase.Applying` — `val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f`, `MimirProgressBar(progress = fraction, modifier = Modifier.fillMaxWidth())`, `Text(SyncTexts.counter(p.done, p.total), style = MaterialTheme.typography.bodySmall)` и `p.current?.let { Text(SyncTexts.actionNow(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }`.
    - При `status == SyncStatus.Idle`: `lastSyncTime?.let { Text("Последняя синхронизация: ${SyncTexts.whenFinished(it, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }`.
    - При `status == SyncStatus.Offline`: `Text(SyncTexts.OFFLINE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`.
  - Если `conflicts.isNotEmpty()`: кликабельная плашка (`Modifier.fillMaxWidth().clip(RoundedCornerShape(MimirTokens.Radius6)).background(MimirTokens.StatusError.copy(alpha = 0.14f)).clickable(onClick = onOpenMainWindow).padding(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space8).testTag("popover_conflicts_banner")`) с `Text(SyncTexts.notificationConflict(conflicts.size), color = MimirTokens.StatusError, style = MaterialTheme.typography.labelLarge)`.
  - Если `updateState` является `UpdateUiState.Available` или `UpdateUiState.ReadyToInstall`: кликабельная акцентная плашка (`Modifier.fillMaxWidth().clip(RoundedCornerShape(MimirTokens.Radius6)).background(MimirTokens.Accent.copy(alpha = 0.14f)).clickable(onClick = onOpenMainWindow).padding(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space8).testTag("popover_update_banner")`) с `Icon(MimirIcons.Sparkles, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))` и `Text(UpdateTexts.available(version), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)`.
  - Панель кнопок:
    - Если `!needsSetup`: `MimirButton(text = if (syncing) SyncTexts.SYNCING else SyncTexts.SYNC_NOW, onClick = { scope.launch { runCatching { model.triggerSync() } } }, modifier = Modifier.fillMaxWidth().testTag("popover_sync_button"), variant = MimirButtonVariant.Primary, loading = syncing, enabled = status == SyncStatus.Idle, icon = { Icon(MimirIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) })`.
    - Кнопка главного окна: `MimirButton(text = if (needsSetup) "Настроить Mimir" else "Открыть Mimir", onClick = onOpenMainWindow, modifier = Modifier.fillMaxWidth().testTag("popover_open_button"), variant = if (needsSetup) MimirButtonVariant.Primary else MimirButtonVariant.Secondary, icon = { Icon(MimirIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp)) })`.
    - Нижний ряд (`Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd)`): `MimirButton(text = "Выход", onClick = onQuit, modifier = Modifier.testTag("popover_quit_button"), variant = MimirButtonVariant.Ghost, icon = { Icon(MimirIcons.Power, contentDescription = null, modifier = Modifier.size(14.dp)) })`.

В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt` добавить 2 теста (`captureTrayPopoverIdleDark` и `captureTrayPopoverSyncingLight`).

- [ ] **Step 4: Запустить тесты `:libs:ui` и проверить компиляцию `wasmJs`**

Run: `rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Закоммитить Task 1**

```bash
rtk git add libs/ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirIcons.kt \
  libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverScreen.kt \
  libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt \
  libs/ui/src/desktopTest/kotlin/dev/mimir/ui/TrayPopoverScreenTest.kt \
  libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt
rtk git commit -m "feat(ui): add TrayPopoverScreen and popover vector icons"
```

---

### Task 2: Нативная стилизация системного окна `PlatformWindowStyler` (`:apps:desktop`)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/window/PlatformWindowStyler.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/window/PlatformWindowStylerTest.kt`

**Interfaces:**
- Consumes:
  - `MimirTokens` (`dev.mimir.ui`)
- Produces:
  - `object PlatformWindowStyler` в пакете `dev.mimir.desktop.window`:
    - `fun apply(window: Window, isDark: Boolean, osName: String = System.getProperty("os.name").orEmpty())`
    - `internal fun toWin32ColorRef(color: Color): Int`
    - `internal fun applyMacRootPaneProperties(rootPane: JRootPane, isDark: Boolean)`

- [ ] **Step 1: Написать падающие тесты `PlatformWindowStylerTest.kt`**

Создать `apps/desktop/src/test/kotlin/dev/mimir/desktop/window/PlatformWindowStylerTest.kt`:
```kotlin
package dev.mimir.desktop.window

import androidx.compose.ui.graphics.Color
import dev.mimir.ui.MimirTokens
import javax.swing.JPanel
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformWindowStylerTest {
    @Test
    fun `toWin32ColorRef converts Compose RGB to Win32 BGR COLORREF format`() {
        // DarkSurface = #262626 -> 0x00262626
        assertEquals(0x00262626, PlatformWindowStyler.toWin32ColorRef(MimirTokens.DarkSurface))
        // LightSurface = #F8F8F8 -> 0x00F8F8F8
        assertEquals(0x00F8F8F8, PlatformWindowStyler.toWin32ColorRef(MimirTokens.LightSurface))
        // DarkText = #DCDDDE (R=0xDC, G=0xDD, B=0xDE) -> BGR = 0x00DEDDDC
        assertEquals(0x00DEDDDC, PlatformWindowStyler.toWin32ColorRef(MimirTokens.DarkText))
        // Accent = #705DCF (R=0x70, G=0x5D, B=0xCF) -> BGR = 0x00CF5D70
        assertEquals(0x00CF5D70, PlatformWindowStyler.toWin32ColorRef(MimirTokens.Accent))
        // Pure red (R=0xFF, G=0x00, B=0x00) -> BGR = 0x000000FF
        assertEquals(0x000000FF, PlatformWindowStyler.toWin32ColorRef(Color(0xFFFF0000)))
    }

    @Test
    fun `applyMacRootPaneProperties configures dark and light appearance and transparent titlebar`() {
        val rootPane = JRootPane()
        PlatformWindowStyler.applyMacRootPaneProperties(rootPane, isDark = true)
        assertEquals("NSAppearanceNameDarkAqua", rootPane.getClientProperty("apple.awt.application.appearance"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(false, rootPane.getClientProperty("apple.awt.windowTitleVisible"))

        PlatformWindowStyler.applyMacRootPaneProperties(rootPane, isDark = false)
        assertEquals("NSAppearanceNameAqua", rootPane.getClientProperty("apple.awt.application.appearance"))
    }
}
```

- [ ] **Step 2: Запустить тест `:apps:desktop:test`, чтобы убедиться, что он падает**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.window.PlatformWindowStylerTest"`
Expected: FAIL (`Unresolved reference 'PlatformWindowStyler'`)

- [ ] **Step 3: Реализовать `PlatformWindowStyler.kt`**

Создать `apps/desktop/src/main/kotlin/dev/mimir/desktop/window/PlatformWindowStyler.kt`:
- `toWin32ColorRef(color: Color): Int`:
  ```kotlin
  val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
  val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
  val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
  return (b shl 16) or (g shl 8) or r
  ```
- `applyMacRootPaneProperties(rootPane: JRootPane, isDark: Boolean)`:
  - выставляет `apple.awt.application.appearance`, `apple.awt.fullWindowContent = true`, `apple.awt.transparentTitleBar = true`, `apple.awt.windowTitleVisible = false`.
- `applyWindowsDwmAttributes(window: Window, isDark: Boolean)` через `java.lang.foreign`:
  - Константы `DWMWA_USE_IMMERSIVE_DARK_MODE = 20`, `DWMWA_BORDER_COLOR = 34`, `DWMWA_CAPTION_COLOR = 35`, `DWMWA_TEXT_COLOR = 36`.
  - Поиск `HWND` окна текущего процесса через `user32.dll` (`FindWindowExW` по классу `"SunAwtFrame"` и проверка `GetWindowThreadProcessId == ProcessHandle.current().pid().toInt()`, fallback на `GetActiveWindow()`).
  - Вызов `dwmapi.dll` → `DwmSetWindowAttribute(hwnd, attr, valueSegment, 4)` в `Arena.ofConfined()`.
- На Linux и прочих ОС `apply` — безопасный no-op.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.window.PlatformWindowStylerTest"`
Expected: PASS

- [ ] **Step 5: Закоммитить Task 2**

```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/window/PlatformWindowStyler.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/window/PlatformWindowStylerTest.kt
rtk git commit -m "feat(desktop): add PlatformWindowStyler for native Win32 DWM and macOS frame styling"
```

---

### Task 3: Контроллер видимости `PopoverVisibilityController` и калькулятор координат `ScreenPositionCalculator` (`:apps:desktop`)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/PopoverVisibilityController.kt`
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculator.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/PopoverVisibilityControllerTest.kt`
- Create: `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculatorTest.kt`

**Interfaces:**
- Produces:
  - `data class TrayClickAnchor(val screenX: Int, val screenY: Int)` в `dev.mimir.desktop.tray`
  - `class PopoverVisibilityController(private val debounceMs: Long = 200L, private val clockMillis: () -> Long = System::currentTimeMillis)` в `dev.mimir.desktop.tray`
  - `data class ScreenGeometry(val bounds: Rectangle, val insets: Insets)` и `object ScreenPositionCalculator` с `fun calculate(screen: ScreenGeometry, popoverSize: Dimension, anchor: TrayClickAnchor? = null, margin: Int = 8, osName: String = System.getProperty("os.name").orEmpty()): Point` в `dev.mimir.desktop.tray`

- [ ] **Step 1: Написать падающие тесты `PopoverVisibilityControllerTest.kt` и `ScreenPositionCalculatorTest.kt`**

Создать `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/PopoverVisibilityControllerTest.kt`:
```kotlin
package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopoverVisibilityControllerTest {
    @Test
    fun `toggles visibility on tray activate when no recent focus loss`() {
        var now = 1_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })
        val anchor = TrayClickAnchor(1800, 1040)

        assertTrue(controller.onTrayActivate(anchor))
        assertTrue(controller.visible)
        assertEquals(anchor, controller.lastAnchor)

        assertFalse(controller.onTrayActivate(anchor))
        assertFalse(controller.visible)
    }

    @Test
    fun `suppresses immediate reopen within debounce window after focus loss`() {
        var now = 5_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })

        assertTrue(controller.onTrayActivate(null))
        now = 5_100L
        assertFalse(controller.onFocusLost())
        assertFalse(controller.visible)

        // Через 50 мс после потери фокуса приходит клик по иконке трея — должен быть подавлен
        now = 5_150L
        assertFalse(controller.onTrayActivate(null))
        assertFalse(controller.visible)

        // После истечения 200 мс — снова открывает поповер
        now = 5_301L
        assertTrue(controller.onTrayActivate(null))
        assertTrue(controller.visible)
    }

    @Test
    fun `dismiss hides popover without triggering focus loss debounce`() {
        var now = 2_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })
        controller.onTrayActivate(null)
        controller.dismiss()
        assertFalse(controller.visible)

        // Повторный клик сразу после явного dismiss() открывает поповер без задержки
        now = 2_010L
        assertTrue(controller.onTrayActivate(null))
    }
}
```

Создать `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculatorTest.kt`:
```kotlin
package dev.mimir.desktop.tray

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenPositionCalculatorTest {
    private val popoverSize = Dimension(320, 380)

    @Test
    fun `windows bottom taskbar places popover at bottom right above taskbar`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1920, 1080),
            insets = Insets(0, 0, 48, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Windows 11")
        assertEquals(Point(1920 - 320 - 8, 1080 - 48 - 380 - 8), pos)
    }

    @Test
    fun `macos top menubar places popover at top right below menubar`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1440, 900),
            insets = Insets(32, 0, 64, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Mac OS X")
        assertEquals(Point(1440 - 320 - 8, 32 + 8), pos)
    }

    @Test
    fun `linux left dock places popover near left edge when left inset dominates`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1920, 1080),
            insets = Insets(0, 60, 0, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Linux")
        assertEquals(Point(60 + 8, 1080 - 380 - 8), pos)
    }

    @Test
    fun `click anchor centers horizontally and clamps within negative multi-monitor bounds`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(-1920, 0, 1920, 1080),
            insets = Insets(0, 0, 40, 0),
        )
        // Клик у самого правого края левого монитора (x = -10, y = 1060)
        val pos = ScreenPositionCalculator.calculate(
            screen = screen,
            popoverSize = popoverSize,
            anchor = TrayClickAnchor(-10, 1060),
            margin = 8,
            osName = "Windows 11",
        )
        assertEquals(Point(-320 - 8, 1080 - 40 - 380 - 8), pos)
    }

    @Test
    fun `tiny screen smaller than popover does not throw on coerceIn`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 200, 200),
            insets = Insets(0, 0, 0, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Linux")
        assertEquals(Point(8, 8), pos)
    }
}
```

- [ ] **Step 2: Запустить тесты `:apps:desktop:test`, чтобы убедиться, что они падают**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.tray.PopoverVisibilityControllerTest" --tests "dev.mimir.desktop.tray.ScreenPositionCalculatorTest"`
Expected: FAIL (`Unresolved reference 'PopoverVisibilityController'`, `Unresolved reference 'ScreenPositionCalculator'`)

- [ ] **Step 3: Реализовать `PopoverVisibilityController.kt` и `ScreenPositionCalculator.kt`**

В `ScreenPositionCalculator.calculate`:
- Вычислить рабочую область: `left = bounds.x + insets.left`, `top = bounds.y + insets.top`, `right = bounds.x + bounds.width - insets.right`, `bottom = bounds.y + bounds.height - insets.bottom`.
- Вычислить безопасные границы зажатия:
  - `minX = left + margin`, `maxX = (right - popoverSize.width - margin).coerceAtLeast(minX)`
  - `minY = top + margin`, `maxY = (bottom - popoverSize.height - margin).coerceAtLeast(minY)`
- Если `anchor != null`:
  - `rawX = anchor.screenX - popoverSize.width / 2`
  - `midY = bounds.y + bounds.height / 2`
  - `rawY = if (anchor.screenY < midY) minY else maxY`
- Если `anchor == null`:
  - `isMac = osName.contains("mac", ignoreCase = true) || osName.contains("os x", ignoreCase = true)`
  - `topBar = isMac || insets.top > insets.bottom`
  - `leftBar = !isMac && insets.left > insets.right && insets.left > insets.bottom && insets.left > insets.top`
  - `rawX = if (leftBar) minX else maxX`
  - `rawY = if (topBar) minY else maxY`
- Вернуть `Point(rawX.coerceIn(minX, maxX), rawY.coerceIn(minY, maxY))`.

- [ ] **Step 4: Запустить тесты и убедиться, что они проходят**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.tray.PopoverVisibilityControllerTest" --tests "dev.mimir.desktop.tray.ScreenPositionCalculatorTest"`
Expected: PASS

- [ ] **Step 5: Закоммитить Task 3**

```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/PopoverVisibilityController.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculator.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/PopoverVisibilityControllerTest.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculatorTest.kt
rtk git commit -m "feat(desktop): add PopoverVisibilityController and ScreenPositionCalculator"
```

---

### Task 4: Расширение `Tray`, окно `TrayPopoverWindow` (`Window.Type.POPUP`) и интеграция в `Main.kt` (`:apps:desktop`)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/NoTray.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt`
- Create: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayPopoverWindow.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayLifecycleTest.kt`

**Interfaces:**
- Consumes:
  - `TrayPopoverScreen` (`dev.mimir.ui.screens`)
  - `PlatformWindowStyler` (`dev.mimir.desktop.window`)
  - `TrayClickAnchor`, `PopoverVisibilityController`, `ScreenPositionCalculator`, `ScreenGeometry` (`dev.mimir.desktop.tray`)
- Produces:
  - `Tray.show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit, onActivate: (TrayClickAnchor?) -> Unit = { onOpen() })`
  - `@Composable fun TrayPopoverWindow(visible: Boolean, anchor: TrayClickAnchor?, model: MimirUiModel?, startupError: String?, onFocusLost: () -> Unit, onDismissRequest: () -> Unit, onOpenMainWindow: () -> Unit, onQuit: () -> Unit)`

- [ ] **Step 1: Написать падающие тесты в `TrayTest.kt` и `TrayLifecycleTest.kt` на маршрутизацию macOS и передачу координат `onActivate`**

В `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt` обновить `FakeLinuxTray.show` и добавить тест macOS:
```kotlin
    @Test
    fun `macos with tray support gets AwtTray`() {
        assertIs<AwtTray>(createTray("Mac OS X", { true }))
    }
```

В `apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayLifecycleTest.kt` проверить, что:
1) `item.Activate(420, 1050)` вызывает `onActivate` с `TrayClickAnchor(420, 1050)`;
2) `item.Activate(0, 0)` вызывает `onActivate` с `null`;
3) клик по пункту `ID_OPEN (1)` в `DbusMenu` вызывает `onOpen()`.

- [ ] **Step 2: Запустить тесты `:apps:desktop:test`, чтобы убедиться, что они падают**

Run: `rtk ./gradlew :apps:desktop:test --tests "dev.mimir.desktop.tray.TrayTest" --tests "dev.mimir.desktop.tray.TrayLifecycleTest"`
Expected: FAIL (`Mac OS X` returns `NoTray`, `onActivate` parameter missing)

- [ ] **Step 3: Обновить `Tray.kt`, `NoTray.kt`, `AwtTray.kt`, `DbusStatusNotifierTray.kt`, создать `TrayPopoverWindow.kt` и подключить всё в `Main.kt`**

1. В `Tray.kt`:
   - Добавить параметр `onActivate: (TrayClickAnchor?) -> Unit = { onOpen() }` в `fun show(...)`.
   - В `createTray`: `(osName.startsWith("Windows") || osName.startsWith("Mac") || osName.contains("OS X")) && awtTraySupported() -> AwtTray()`.
2. В `NoTray.kt`:
   - Обновить сигнатуру `override fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit, onActivate: (TrayClickAnchor?) -> Unit) = Unit`.
3. В `AwtTray.kt`:
   - Создавать `TrayIcon(image, status, null)` без `java.awt.PopupMenu`.
   - Добавить `MouseAdapter`:
     - в `mouseReleased(e: MouseEvent)`: если `e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)` — вызвать `onOpen()`, иначе — `onActivate(TrayClickAnchor(e.xOnScreen, e.yOnScreen))`.
4. В `DbusStatusNotifierTray.kt`:
   - Пробросить `onActivate: (TrayClickAnchor?) -> Unit` в `Item`.
   - В `Item.Activate(x: Int, y: Int)` вызывать `onActivate(if (x > 0 || y > 0) TrayClickAnchor(x, y) else null)`.
   - `SecondaryActivate` и пункт `ID_OPEN` в `Menu` по-прежнему вызывают `onOpen()`.
5. Создать `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayPopoverWindow.kt`:
   - Использовать `Window(onCloseRequest = onDismissRequest, visible = visible, undecorated = true, resizable = false, alwaysOnTop = true, focusable = true, state = rememberWindowState(size = DpSize(320.dp, 380.dp)), onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismissRequest(); true } else false })`.
   - В `DisposableEffect(window)` выставлять `window.type = java.awt.Window.Type.POPUP` и регистрировать `WindowFocusListener` (`windowLostFocus` → `onFocusLost()`).
   - В `LaunchedEffect(visible, anchor)`: при `visible == true` вычислять позицию через `ScreenPositionCalculator.calculate(...)` для монитора курсора/клик-якоря (через `GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices`) и устанавливать `window.setLocation(point.x, point.y)` + `window.toFront()` + `window.requestFocus()`.
   - Внутри окна оборачивать рендеринг в `if (visible) { MimirTheme { TrayPopoverScreen(model = model, startupError = startupError, onOpenMainWindow = onOpenMainWindow, onQuit = onQuit) } }`.
6. В `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`:
   - Добавить `val popoverController = remember { PopoverVisibilityController() }`, `var popoverVisible by remember { mutableStateOf(false) }` и `var popoverAnchor by remember { mutableStateOf<TrayClickAnchor?>(null) }`.
   - Добавить `val mainWindowRef = remember { AtomicReference<java.awt.Window?>(null) }` и функцию `val openMainWindow: () -> Unit = { scope.launch(Dispatchers.Main) { popoverController.dismiss(); popoverVisible = false; visible = true; mainWindowRef.get()?.let(PlatformFocus::activate) } }`.
   - При вызове `tray.show(...)` передавать `onOpen = openMainWindow` и `onActivate = { anchor -> scope.launch(Dispatchers.Main) { popoverVisible = popoverController.onTrayActivate(anchor); popoverAnchor = popoverController.lastAnchor } }`.
   - Внутри главного `Window`: определять `val isDark = isSystemInDarkTheme()` и в `LaunchedEffect(window, isDark)` сохранять `mainWindowRef.set(window)` и вызывать `PlatformWindowStyler.apply(window, isDark)`.
   - Рендерить `TrayPopoverWindow(visible = popoverVisible, anchor = popoverAnchor, model = model, startupError = startupError, onFocusLost = { popoverVisible = popoverController.onFocusLost() }, onDismissRequest = { popoverController.dismiss(); popoverVisible = false }, onOpenMainWindow = openMainWindow, onQuit = ::exitApplication)`.

- [ ] **Step 4: Запустить полную проверку JVM-тестов, скриншотов и базы знаний**

Run: `rtk mise run ci:fast && rtk mise run screenshots && rtk context-init check --compact`
Expected: BUILD SUCCESSFUL, все тесты и скриншоты зелёные, дрейф базы знаний отсутствует (либо обновлён через `rtk context-init update . --compact`).

- [ ] **Step 5: Закоммитить Task 4**

```bash
rtk git add apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/NoTray.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt \
  apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayPopoverWindow.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt \
  apps/desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayLifecycleTest.kt
rtk git commit -m "feat(desktop): integrate TrayPopoverWindow and PlatformWindowStyler"
```
