---
name: mimir-ui-test
description: Use when creating, modifying or verifying UI screens and components in Mimir (desktop Compose or browser extension) through headless automated testing and screenshot capture — UI regression, visual defects, layout verification, light and dark theme checks.
---

# Скриншотное и UI-тестирование в Mimir

## Суть

UI Mimir работает на нескольких платформах (десктоп, веб-расширение, Android). Тестирование интерфейса выполняется **строго в headless-режиме** (без активного X11/Wayland дисплея) для гарантии работоспособности в CI и на машинах разработчиков.

Скриншотное тестирование визуально подтверждает корректность геометрии, тем оформления (светлая и тёмная) и текстов без ручного прокликивания.

## Инструменты и механизмы

| Платформа | Инструмент | Механизм | Эталон в коде |
|---|---|---|---|
| **Desktop Compose (`:libs:ui`)** | Compose Multiplatform Test + Skia | `captureToImage().toAwtImage()` на оффскрин-поверхности Skia в `runDesktopComposeUiTest` | `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt` |
| **Browser Extension (`:apps:browser-extension`)** | Chromium Headless (`--headless=new`) + Node.js | Загрузка `popup.html?mock=<state>` с подстановкой `FakeExtensionPlatform` | `apps/browser-extension/scripts/capture-screenshots.mjs` |

## Команды

```bash
# Полный прогон всех скриншотов (desktop + extension):
rtk mise run screenshots

# Запуск только Compose UI скриншотов:
rtk ./gradlew :libs:ui:desktopTest --rerun --tests 'dev.mimir.ui.screenshots.*'

# Запуск только скриншотов расширения (требует предварительной сборки расширения):
rtk ./gradlew :apps:browser-extension:assembleExtension && node apps/browser-extension/scripts/capture-screenshots.mjs
```

Скриншоты сохраняются в:
- `build/reports/screenshots/desktop/*.png`
- `build/reports/screenshots/extension/*.png`

## Шаблон теста Desktop Compose

Desktop-тесты используют `FakeMimirUiModel` с детерминированным состоянием и `onRoot().captureToImage()`:

```kotlin
@OptIn(ExperimentalTestApi::class)
class MyScreenScreenshotTest {
    @Test
    fun testScreenDarkAndLight() = runDesktopComposeUiTest {
        val model = FakeMimirUiModel()
        // Настройка состояния модели...

        setContent {
            MimirTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MyScreen(model = model)
                }
            }
        }

        val darkImage = onRoot().captureToImage().toAwtImage()
        saveScreenshot("my_screen_dark.png", darkImage)
    }
}
```

## Шаблон тестирования Browser Extension

Расширение запускается локально без Google Cloud и Chrome Extension API:
1. `Popup.kt` считывает параметр URL: `?mock=auth`, `?mock=unlock`, `?mock=vault` или автоматически переключается на `FakeExtensionPlatform` при отсутствии `chrome.storage.local`.
2. Тестовое хранилище заметок инициализируется через `InMemoryRemoteStorage`.
3. Скрипт `capture-screenshots.mjs` эмулирует размеры Side Panel (`400x600`) и Tab (`1024x768`) с флагом `--force-dark-mode` или светлой темой.

## Правила и инварианты

1. **Обе темы обязательны:** каждый экран и компонент проверяются как в тёмной (`MimirTokens.DarkSurface`), так и в светлой теме (`MimirTokens.LightSurface`).
2. **Нулевые сетевые вызовы:** запрещено обращаться к реальному Google Drive или удалённому демону. Использовать только `FakeMimirUiModel` или `FakeExtensionPlatform`.
3. **Фиксированные размеры окон:**
   - Desktop Wizard: 720×520 dp.
   - Desktop Status: 400×480 dp.
   - Extension Panel: 400×600 px.
   - Extension Tab: 1024×768 px.
4. **Контроль геометрии:**
   - Поля ввода и кнопки в одной строке строго одной высоты (56 dp / 44 dp).
   - Внутри `OutlinedTextField` использовать `placeholder`, а не `label`.
   - Иконки — векторные `MimirIcons`, никаких emoji (`📁`, `👁`).

## Красные флаги

- Ручная проверка «на глаз» через запуск всего приложения (`mise run desktop`) вместо запуска автоматических тестов.
- Тест UI, требующий работающего X11-дисплея или переменной `DISPLAY`.
- Игнорирование светлой темы (проверка только тёмной).
- Вызов `chrome.storage` в расширении без безопасной проверки на существование API в headless-среде.
