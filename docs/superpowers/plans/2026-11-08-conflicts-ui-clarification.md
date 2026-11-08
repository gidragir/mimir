# Conflicts UI Clarification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Уточнить терминологию и UX окна конфликтов: вынести строки в `ConflictsTexts`, перевести карточку на `MimirCard`/`MimirButton`, добавить контекстные подсказки к кнопкам («Оставить из облака» / «Оставить локальную») и обновить тесты.

**Architecture:** Вынести все текстовые константы из `ConflictsScreen` в объект `ConflictsTexts`. Заменить базовые компоненты Material 3 `Card` и `Button`/`OutlinedButton` на крафтовые компоненты дизайн-системы `MimirCard` и `MimirButton`. Добавить подписи действий, объясняющие последствия выбора каждой версии для основного и дублирующего файлов. Обновить юнит-тесты Compose в `desktopTest`.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12, MimirTokens, Compose Desktop Testing API.

**Spec:** [docs/superpowers/specs/2026-11-08-conflicts-ui-clarification-design.md](file:///home/gidragir/projects/mimir/docs/superpowers/specs/2026-11-08-conflicts-ui-clarification-design.md)

## Global Constraints
- Все строки пользовательского интерфейса экрана конфликтов обязаны находиться в объекте `dev.mimir.ui.screens.ConflictsTexts`.
- Никаких raw emoji в кнопках и карточках (скилл `mimir-ui-ux`).
- Высота touch target на сенсорных экранах обязана быть не менее 48 dp (скилл `mimir-ui-ux`, тест `MobileAdaptationTest`).
- Строгая работа на Arch Linux, сборка через Gradle `:libs:ui:desktopTest`.

## Review Focus
1. Длинные пути файлов (`entry.originalPath` / `entry.conflictPath`): не должны ломать верстку карточки или вызывать горизонтальный оверфлоу.
2. Размер touch target: на тач-экранах (`LocalTouchInput provides true`) кнопки сохраняют высоту не менее 48 dp.
3. Сохранение обратной совместимости API: методы `MimirUiModel.resolveConflict` и параметры `keepConflict: Boolean` остаются неизменными.
4. Поведение при ошибке: сообщение об ошибке отображается текстом ошибки в карточке, состояние не крашится.

## Инварианты Mimir
1. **Платформы:** `libs/ui` (Compose Multiplatform) — код работает единообразно на Desktop, Android и Wasm без платформенных условий.
2. **Дизайн:** Использование `MimirCard`, `MimirButton`, токенов `MimirTokens.Space*` и `ConflictsTexts` (паттерн внешних текстов, как в `SyncTexts`).
3. **IO:** Неприменимо (чистый UI-слой, IO выполняется асинхронно внутри рантайма/демона).
4. **UX:** Полная таблица состояний экрана (пусто, список конфликтов, прогресс/клик, ошибка разрешения) с понятными человеку подписями действий.

---

### Task 1: Create `ConflictsTexts` and Update Failing Tests

**Files:**
- Create: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsTexts.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt:18-87`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MobileAdaptationTest.kt:35-45`

**Interfaces:**
- Produces: `ConflictsTexts` object in `dev.mimir.ui.screens`:
  ```kotlin
  object ConflictsTexts {
      const val TITLE = "Конфликты"
      const val BACK = "Назад"
      const val NO_CONFLICTS = "Конфликтов не обнаружено"
      const val RESOLVE_FAILED = "Не удалось разрешить конфликт"
      const val CREATED_PREFIX = "Создан: "
      const val CONFLICT_COPY_LABEL = "Конфликтная копия: "
      const val KEEP_CLOUD_BUTTON = "Оставить из облака"
      const val KEEP_CLOUD_HINT = "Сохранит версию Drive, удалит копию"
      const val KEEP_LOCAL_BUTTON = "Оставить локальную"
      const val KEEP_LOCAL_HINT = "Заменит файл локальной версией, удалит копию"
  }
  ```

- [ ] **Step 1: Create `ConflictsTexts.kt`**
  Создать файл `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsTexts.kt` со всеми строковыми константами.

- [ ] **Step 2: Update tests to expect new button texts and hints**
  В `ConflictsScreenTest.kt` заменить поиски старых строк `"Оставить оригинал"` и `"Оставить копию"` на использование `ConflictsTexts.KEEP_CLOUD_BUTTON` и `ConflictsTexts.KEEP_LOCAL_BUTTON`. Добавить проверку отображения `ConflictsTexts.KEEP_CLOUD_HINT` и `ConflictsTexts.KEEP_LOCAL_HINT`.
  В `MobileAdaptationTest.kt` заменить `"Оставить оригинал"` на `ConflictsTexts.KEEP_CLOUD_BUTTON`.

- [ ] **Step 3: Run tests to verify they fail**
  Выполнить `rtk ./gradlew :libs:ui:desktopTest --tests dev.mimir.ui.ConflictsScreenTest`.
  Ожидается: FAIL (кнопки с новым текстом ещё не отображаются в `ConflictsScreen`).

- [ ] **Step 4: Commit test updates**
  ```bash
  git add libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsTexts.kt libs/ui/src/desktopTest/
  git commit -m "test(ui): define ConflictsTexts and update conflict screen tests"
  ```

---

### Task 2: Implement Redesigned `ConflictsScreen` with Design System Components

**Files:**
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt:1-109`

**Interfaces:**
- Consumes: `ConflictsTexts`, `MimirCard`, `MimirButton`, `MimirTokens`, `MimirUiModel`.
- Produces: Обновленный `@Composable fun ConflictsScreen(model: MimirUiModel, onBack: () -> Unit)`.

- [ ] **Step 1: Refactor `ConflictsScreen.kt`**
  - Заменить `Card` на `MimirCard`.
  - Заменить сырые кнопки на `MimirButton` (`variant = MimirButtonVariant.Primary` для облачной версии, `variant = MimirButtonVariant.Secondary` для локальной версии).
  - Разместить под каждой кнопкой подсказку (`ConflictsTexts.KEEP_CLOUD_HINT` и `ConflictsTexts.KEEP_LOCAL_HINT`) стилем `MaterialTheme.typography.bodySmall` и приглушенным цветом.
  - Заменить все захардкоженные строки ("Назад", "Конфликты", "Конфликтов не обнаружено", "Создан: ", "Не удалось разрешить конфликт") на константы из `ConflictsTexts`.
  - Убедиться, что вызов `resolve(entry, keepConflict = false)` привязан к `KEEP_CLOUD_BUTTON`, а `resolve(entry, keepConflict = true)` привязан к `KEEP_LOCAL_BUTTON`.

- [ ] **Step 2: Run tests to verify they pass**
  Выполнить `rtk ./gradlew :libs:ui:desktopTest`.
  Ожидается: BUILD SUCCESSFUL, все тесты `ConflictsScreenTest` и `MobileAdaptationTest` проходят.

- [ ] **Step 3: Commit UI implementation**
  ```bash
  git add libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt
  git commit -m "feat(ui): redesign conflicts screen with clear cloud vs local actions and Mimir components"
  ```
