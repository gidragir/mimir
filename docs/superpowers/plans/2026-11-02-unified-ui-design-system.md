# Mimir Unified UI Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создание единого, современного и визуально выверенного интерфейса Mimir (уровень Obsidian / Linear Craft) на всех платформах: автоматическая генерация дизайн-токенов из единого источника истины (`design/tokens.json`), разработка фирменных кастомных компонентов в Compose Multiplatform (`:ui`), глубокий редизайн `browser-extension` и подготовка каркаса `web-ui`.

**Architecture:** Единый декларативный файл `design/tokens.json` определяет визуальный язык (палитра Obsidian, радиусы, типографика, отступы). Микрогенератор `scripts/generate-tokens.mjs` транслирует токены в Kotlin Compose (`dev.mimir.ui.MimirTokens`) и веб (`tokens.css`). Модуль `:ui` получает набор фирменных компонентов (`MimirButton`, `MimirTextField`, `MimirCard`, `MimirStatusBadge`, `MimirProgressBar`), заменяя дефолтные сырые примитивы Material 3. `browser-extension` переводится на сгенерированные токены с обновлением визуального ряда `popup.css`.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12, Node.js (scripts), CSS Custom Properties, pnpm.

**Spec:** `docs/superpowers/specs/2026-11-02-unified-ui-components-design.md` и аудиторский отчет `docs/superpowers/specs/2026-11-02-unified-ui-components-audit-report.md`.

## Global Constraints

- Машина разработки — Arch Linux (bash). На Windows ничего не запускать.
- Сборка и тесты проходят без секретов и без Android SDK (`mise run test`, `mise run build`).
- Идентификаторы (`mimir.*`) живут только в `gradle.properties`, версии — в `gradle/libs.versions.toml`.
- Палитра Obsidian: фон Dark `#1E1E1E` / Light `#FFFFFF`, акцент `#705DCF` (ховер `#826FE0`), рамки `#363636` / `#D4D4D4`, инпуты `#141414` / `#F0F0F0`.
- Шкала отступов: 4, 8, 12, 16, 24, 32 px; радиусы: 4, 6, 8 px; высота элементов: 32 px (desktop/web) и 48 px (touch).
- Все 7 обязательных состояний экрана по `mimir-ui-ux` (Loading, Empty, Error, Offline, InProgress, Success, Destructive).
- Тексты — строго на русском языке, без технических терминов («хэш», «индекс», «ревизия»). Статус до подключения — ровно `Mimir: not connected`.
- TDD: сначала падающий тест, затем реализация. Коммиты — conventional (`feat(ui): ...`).

## Review Focus

- **Визуальный дрейф токенов:** изменение в `design/tokens.json` обязано вызывать рассинхронизацию в тесте, пока не запущен `node scripts/generate-tokens.mjs`. → тест в Task 1.
- **Интерактивные состояния полей ввода (Password / Focus):** поле пароля скрывает/показывает символы по кнопке, фокусная рамка имеет акцентное свечение, текст ошибки не ломает верстку. → тест в Task 2.
- **Состояние Offline в статусе:** при отключении сети бейдж статуса окрашивается в нейтрально-серый цвет, а текст сообщает о сохранении изменений. → тест в Task 3.
- **Ограниченный размер Chrome Extension Popup (320 px):** тулбар, дерево файлов и ридер не обрезаются и не вызывают горизонтальной прокрутки при минимальной ширине окна. → тест в Task 4.
- **Контрастность и доступность:** текст на поверхностях соответствует коэффициенту контрастности ≥ 4.5:1, клавиатурный фокус виден в обеих темах. → тест в Task 2 и 4.

---

## File Structure

| Файл | Ответственность |
|---|---|
| `design/tokens.json` (new) | Single Source of Truth для дизайн-токенов Mimir |
| `scripts/generate-tokens.mjs` (new) | Генератор Kotlin Compose констант и CSS Custom Properties |
| `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt` (new/gen) | Сгенерированные константы цветов, радиусов и отступов Compose |
| `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt` | Интеграция `MimirTokens` в тему и палитру приложения |
| `ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirComponents.kt` (new) | Фирменные компоненты: Button, TextField, Card, StatusBadge, ProgressBar |
| `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt` | Редизайн экрана статуса на новых компонентах |
| `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt` | Редизайн мастера настройки на новых компонентах |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt` (new) | Тесты состояний новых компонентов и токенов |
| `browser-extension/src/jsMain/resources/tokens.css` (new/gen) | Сгенерированные CSS переменные дизайн-системы |
| `browser-extension/src/jsMain/resources/popup.css` | Обновленные утонченные стили расширения |
| `browser-extension/src/jsMain/resources/popup.html` | Подключение `tokens.css` |

---

### Task 1: Single Source of Truth дизайн-токенов (`design/tokens.json` и `generate-tokens.mjs`)

**Files:**
- Create: `design/tokens.json`
- Create: `scripts/generate-tokens.mjs`
- Test: `scripts/test-tokens.mjs` (or npm run tokens:check)
- Gen: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt`
- Gen: `browser-extension/src/jsMain/resources/tokens.css`

**Interfaces:**
- Produces: `design/tokens.json`, `MimirTokens.kt` (object `MimirTokens`), `tokens.css` (`:root` и `@media (prefers-color-scheme: dark)`).

- [ ] **Step 1: Создать `design/tokens.json`**

Зафиксировать точные цвета Obsidian, уровни поверхностей (`base`, `surface`, `input`, `border`), акцент (`#705DCF`), статусные цвета (`success #4EAF51`, `error #E5736A`, `offline #8A8A8A`), радиусы (4, 6, 8) и отступы (4, 8, 12, 16, 24, 32).

- [ ] **Step 2: Написать генератор `scripts/generate-tokens.mjs`**

Скрипт на чистом Node.js (ESM), который читает `design/tokens.json`, генерирует `MimirTokens.kt` (для Kotlin Compose) и `tokens.css` (для веба), а при флаге `--check` завершается с кодом 1, если файлы рассинхронизированы.

- [ ] **Step 3: Проверить генерацию и работу проверки рассинхронизации**

Run: `node scripts/generate-tokens.mjs && node scripts/generate-tokens.mjs --check`  
Expected: PASS, код возврата 0.

- [ ] **Step 4: Добавить скрипт в `package.json`**

Добавить команды `"tokens:generate": "node scripts/generate-tokens.mjs"` и `"tokens:check": "node scripts/generate-tokens.mjs --check"`.

- [ ] **Step 5: Закоммитить изменения**

```bash
git add design/ scripts/generate-tokens.mjs package.json ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt browser-extension/src/jsMain/resources/tokens.css
git commit -m "feat(design): introduce single source of truth design tokens"
```

---

### Task 2: Фирменные компоненты Mimir в Compose (`:ui`)

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirComponents.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/ObsidianStyle.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MimirButton(text: String, onClick: () -> Unit, modifier: Modifier, variant: MimirButtonVariant, loading: Boolean, enabled: Boolean)`
  - `@Composable fun MimirTextField(value: String, onValueChange: (String) -> Unit, isPassword: Boolean, error: String?, ...)`
  - `@Composable fun MimirCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun MimirStatusBadge(status: SyncStatus, modifier: Modifier)`
  - `@Composable fun MimirProgressBar(progress: Float?, modifier: Modifier)`

- [ ] **Step 1: Написать падающий тест для компонентов `MimirComponentsTest.kt`**

Проверить рендеринг состояний: `MimirButton` (loading, disabled), `MimirTextField` (password toggle visibility), `MimirStatusBadge` (Connected, Syncing, Offline, Error).

- [ ] **Step 2: Запустить тест и убедиться в ошибке компиляции/падении**

Run: `./gradlew :ui:desktopTest --tests "dev.mimir.ui.MimirComponentsTest"`  
Expected: FAIL (Unresolved reference: MimirComponents).

- [ ] **Step 3: Реализовать фирменные компоненты в `MimirComponents.kt`**

Использовать точные значения из `MimirTokens`: радиусы 6px для кнопок и инпутов, 8px для карточек, 1px тонкие границы, светящаяся точка статуса с мягким радиусом, компактный режим 32px для десктопа.

- [ ] **Step 4: Запустить тест и убедиться в прохождении**

Run: `./gradlew :ui:desktopTest --tests "dev.mimir.ui.MimirComponentsTest"`  
Expected: PASS.

- [ ] **Step 5: Закоммитить изменения**

```bash
git add ui/src/commonMain/kotlin/dev/mimir/ui/components/ ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt
git commit -m "feat(ui): implement crafted Mimir design system components"
```

---

### Task 3: Редизайн экранов `StatusScreen` и `SetupWizardScreen` (`:ui`)

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt`

**Interfaces:**
- Consumes: `MimirButton`, `MimirTextField`, `MimirCard`, `MimirStatusBadge`, `MimirProgressBar`.
- Preserves: Сигнатуры `StatusScreen(model: MimirUiModel, ...)`, `SetupWizardScreen(model: MimirUiModel, ...)`.

- [ ] **Step 1: Запустить существующие тесты экранов**

Run: `./gradlew :ui:desktopTest --tests "dev.mimir.ui.StatusScreenTest"`  
Expected: PASS.

- [ ] **Step 2: Переработать верстку `StatusScreen.kt` и `SetupWizardScreen.kt`**

Заменить дефолтные `Card`, `Button`, `OutlinedTextField` на фирменные `MimirCard`, `MimirButton`, `MimirTextField`, `MimirStatusBadge`. Отрегулировать отступы по шкале 8/12/16/24 dp, добавить четкую иерархию заголовков и статусных карточек.

- [ ] **Step 3: Запустить тесты экранов и убедиться в прохождении**

Run: `./gradlew :ui:desktopTest`  
Expected: All desktop tests PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/
git commit -m "feat(ui): redesign StatusScreen and SetupWizardScreen with crafted components"
```

---

### Task 4: Редизайн `browser-extension` (стили и верстка)

**Files:**
- Modify: `browser-extension/src/jsMain/resources/popup.html`
- Modify: `browser-extension/src/jsMain/resources/popup.css`
- Modify: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt`
- Test: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/PopupDomViewTest.kt`

**Interfaces:**
- Consumes: `tokens.css` (CSS Custom Properties).

- [ ] **Step 1: Подключить `tokens.css` в `popup.html`**

Добавить `<link rel="stylesheet" href="tokens.css">` перед `popup.css`.

- [ ] **Step 2: Обновить `popup.css`**

Заменить жестко захардкоженные цвета и грубые стили на токены:
- Использовать `--mimir-bg`, `--mimir-bg-surface`, `--mimir-bg-input`, `--mimir-accent`.
- Инпуты: темный инсет-фон `#141414`, акцентный outline `0 0 0 1px var(--mimir-accent)`.
- Кнопки: аккуратный радиус 6px, деликатный ховер, отсутствие резких контрастов.
- Статусные точки: мягкое свечение `box-shadow: 0 0 8px var(--mimir-accent)`.
- Дерево заметок: ховер с фоном `var(--mimir-accent-subtle)`, индикатор активной заметки.

- [ ] **Step 3: Запустить тесты расширения**

Run: `./gradlew :browser-extension:jsTest && node browser-extension/scripts/check-extension.mjs`  
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
git add browser-extension/src/jsMain/resources/ browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/
git commit -m "feat(extension): apply modern Obsidian design system to browser extension"
```

---

### Task 5: Сквозная верификация и контроль качества

**Files:**
- Test: All tests in monorepo

- [ ] **Step 1: Проверить валидность токенов**

Run: `node scripts/generate-tokens.mjs --check`  
Expected: PASS.

- [ ] **Step 2: Запустить полный набор тестов проекта**

Run: `./gradlew allTests`  
Expected: All tests in all modules PASS.

- [ ] **Step 3: Проверить версию changesets**

Run: `pnpm run version:check`  
Expected: PASS.

- [ ] **Step 4: Закоммитить итоговые правки (если есть)**

```bash
git commit -m "chore(design): verify and finalize unified UI design system"
```
