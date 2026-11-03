# Web UI (Preact v11.1.1 SPA) для Docker-контейнера Mimir Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создать сверхлегковесный (< 45 КБ gzip), быстрый (< 50 мс cold start) Web UI на базе Preact v11.1.1 + Vite + Tailwind CSS v4, упакованный в Docker-контейнер и раздаваемый Ktor-демоном, полностью повторяющий функционал и стиль десктопного приложения (авторизация Browser OAuth / Device Code, мастер хранилища, ввод пароля, мониторинг прогресса, разрешение конфликтов).

**Architecture:** Модуль `web-ui` реализуется как легковесный Preact SPA с реактивным управлением состоянием через `@preact/signals` и стилизацией по дизайн-токенам `tokens.css`. Ktor Server (`:daemon`) раздает артефакты `dist/` через статический роутинг с поддержкой SPA fallback. Сборка фронтенда прозрачно связывается с задачей `:daemon:processResources` через Gradle Exec.

**Tech Stack:** Preact v11.1.1, `@preact/signals`, Vite 6, TypeScript 5.7, Tailwind CSS v4, Vitest, Ktor Server CIO 3.x, Kotlin Multiplatform 2.4.21.

**Spec:** `docs/superpowers/specs/2026-11-03-web-ui-docker-design.md`

## Global Constraints

- Версия Preact строго `11.1.1` (`"preact": "11.1.1"`).
- Дизайн-токены генерируются строго из `design/tokens.json` через `scripts/generate-tokens.mjs`.
- Контейнер Docker локально запускается с `--network=host` для прозрачной работы `BrowserLoginReceiver` на `127.0.0.1`.
- Сборка `./gradlew build` обязана успешно проходить даже без установленного Node/pnpm (через fallback-заглушку `dist/index.html`).
- Все HTTP DTO демона сохраняют обратную совместимость с `@EncodeDefault(EncodeDefault.Mode.NEVER)`.

## Review Focus

- **Потеря сессии при перезагрузке страницы:** Проверить восстановление состояния из API демона без застревания в промежуточных шагах визарда.
- **Отказ браузерной авторизации при удаленном доступе:** Проверить переключение на Device Code Flow, если loopback-коллбек недоступен.
- **SPA Fallback при прямом переходе по подпутям:** Проверить, что любой не-API запрос возвращает `index.html` со статусом `200 OK`.
- **Защищенный демон (`MIMIR_TOKEN`):** Проверить передачу токена через `?token=...` и сохранение в `localStorage` при выполнении запросов.
- **Безопасная персистентность пароля:** Проверить установку прав `0600` на файл пароля при флаге `remember: true`.

---

### Task 1: Backend DTO & API Expansion for Password Persistence & Browser Callback

**Files:**
- Modify: `client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt:20-30`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt:140-160`
- Modify: `runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt:50-70`
- Test: `client/src/commonTest/kotlin/dev/mimir/client/DaemonDtoSerializationTest.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/RoutesTest.kt`

**Interfaces:**
- Consumes: `DaemonUnlockRequest(password: String, remember: Boolean = false)`
- Produces: `POST /api/v1/unlock` с поддержкой `remember`, файл пароля `$configDir/password` (0600), веб-совместимый HTML в `BrowserLoginReceiver`.

- [ ] **Step 1: Write the failing serialization and route tests**

Добавить тест десериализации `DaemonUnlockRequest` с полем `remember = true` и тест вызова `POST /api/v1/unlock` с `remember = true`, проверяющий сохранение пароля.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :client:commonTest --tests "*DaemonDtoSerializationTest*"`
Expected: FAIL (поле `remember` отсутствует).

- [ ] **Step 3: Implement `remember` in `DaemonUnlockRequest` and `Routes.kt`**

В `DaemonDto.kt`:
```kotlin
@Serializable
data class DaemonUnlockRequest(
    val password: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val remember: Boolean = false,
)
```
В `Routes.kt` при обработке `/unlock`: если `req.remember == true`, записать пароль в `config.configDir.resolve("password")` с правами `0600`.
В `BrowserLoginReceiver.kt`: если редирект не задан или запрос веб-клиента, страница отдает `window.close()` вместо `mimir://authorized`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk ./gradlew :client:commonTest :daemon:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt \
        daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt \
        runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt \
        client/src/commonTest/ \
        daemon/src/test/
git commit -m "feat(daemon): support password persistence flag and web-friendly oauth callback"
```

---

### Task 2: Web UI Project Scaffolding & Design Tokens Integration

**Files:**
- Create: `web-ui/package.json`
- Create: `web-ui/tsconfig.json`
- Create: `web-ui/vite.config.ts`
- Create: `web-ui/index.html`
- Create: `web-ui/src/styles/app.css`
- Modify: `scripts/generate-tokens.mjs:10-20`
- Test: `web-ui/tests/setup.test.ts`

**Interfaces:**
- Consumes: `design/tokens.json`
- Produces: `web-ui/src/styles/tokens.css`, Vite-сборка `@mimir/web-ui`.

- [ ] **Step 1: Write tokens generator output update and test**

Обновить `scripts/generate-tokens.mjs`, добавив генерацию `web-ui/src/styles/tokens.css`.
Запустить `pnpm run tokens:generate` и проверить создание файла.

- [ ] **Step 2: Scaffold `web-ui` configuration files**

Создать `web-ui/package.json`:
```json
{
  "name": "@mimir/web-ui",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
  "dependencies": {
    "preact": "11.1.1",
    "@preact/signals": "^2.11.4"
  },
  "devDependencies": {
    "@preact/preset-vite": "^2.10.1",
    "@tailwindcss/vite": "^4.0.0",
    "tailwindcss": "^4.0.0",
    "typescript": "^5.7.0",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  }
}
```
Создать `vite.config.ts` с `@preact/preset-vite` и `@tailwindcss/vite`, базовый путь `./`, выходной каталог `dist`.

- [ ] **Step 3: Create `app.css` with tokens import**

В `web-ui/src/styles/app.css`:
```css
@import "./tokens.css";
@import "tailwindcss";
```

- [ ] **Step 4: Install dependencies and verify build**

Run: `rtk pnpm --filter @mimir/web-ui install && rtk pnpm --filter @mimir/web-ui build`
Expected: Генерация `dist/index.html` и ассетов.

- [ ] **Step 5: Commit**

```bash
git add web-ui/ package.json pnpm-lock.yaml scripts/generate-tokens.mjs
git commit -m "chore(web-ui): scaffold preact 11.1.1 SPA with vite and tailwind v4"
```

---

### Task 3: Web UI Core API Client & Reactive Store

**Files:**
- Create: `web-ui/src/api/types.ts`
- Create: `web-ui/src/api/client.ts`
- Create: `web-ui/src/state/store.ts`
- Test: `web-ui/tests/api-client.test.ts`
- Test: `web-ui/tests/store.test.ts`

**Interfaces:**
- Consumes: REST API `/api/v1/*` демона.
- Produces: `DaemonApiClient`, сигналы `statusSignal`, `progressSignal`, `isLockedSignal`, `conflictsSignal`.

- [ ] **Step 1: Write failing unit tests for API client and store**

Создать тесты:
- `api-client.test.ts`: проверка формирования заголовков `Authorization: Bearer`, запросов `/health`, `/status`, `/unlock`, `/auth/browser/start`.
- `store.test.ts`: проверка обновления сигналов при поллинге и переходах состояний.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: FAIL (модули отсутствуют).

- [ ] **Step 3: Implement `types.ts`, `client.ts` and `store.ts`**

`types.ts`: DTO `SyncStatus`, `ProgressDto`, `DaemonLoginStartDto`, `ConflictFileDto`, `VaultInspectionDto`.
`client.ts`: `DaemonApiClient` с методами `getStatus()`, `triggerSync()`, `unlock(pwd, remember)`, `inspectVault()`, `resolveConflict()`, `startBrowserLogin()`, `pollBrowserLogin()`, `startDeviceLogin()`, `pollDeviceLogin()`.
`store.ts`: Сигналы `status`, `progress`, `isLocked`, `conflicts`, `vaultPath`, `accountEmail`. Метод `startPolling(intervalMs)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/api/ web-ui/src/state/ web-ui/tests/
git commit -m "feat(web-ui): implement typed api client and reactive signals store"
```

---

### Task 4: Reusable Design System Components

**Files:**
- Create: `web-ui/src/components/Button.tsx`
- Create: `web-ui/src/components/Input.tsx`
- Create: `web-ui/src/components/Card.tsx`
- Create: `web-ui/src/components/CircularProgress.tsx`
- Create: `web-ui/src/components/Badge.tsx`
- Create: `web-ui/src/components/Icons.tsx`
- Test: `web-ui/tests/components.test.tsx`

**Interfaces:**
- Consumes: CSS-переменные из `tokens.css`.
- Produces: Набор презентационных компонентов в стиле Obsidian/Linear.

- [ ] **Step 1: Write component tests**

Тесты проверки рендеринга `Button` (primary, secondary, disabled), `Input` (error state, password toggle), `CircularProgress` (progress percentage calculation).

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: FAIL.

- [ ] **Step 3: Implement components**

Реализовать компоненты с поддержкой темных токенов:
- `Button.tsx`: варианты `primary` (акцент `#705dcf`), `secondary`, `danger`.
- `Input.tsx`: поле ввода со стилизацией фокуса и отображением сообщений об ошибках.
- `CircularProgress.tsx`: SVG кольцо прогресса с анимацией `stroke-dashoffset`.
- `Icons.tsx`: inline SVG иконки (Sync, Check, Alert, Google, Folder, Lock).

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/components/ web-ui/tests/components.test.tsx
git commit -m "feat(web-ui): implement obsidian-themed design components"
```

---

### Task 5: Setup Wizard Screens (Vault, Google, Password, InitialResolution)

**Files:**
- Create: `web-ui/src/screens/setup/VaultStep.tsx`
- Create: `web-ui/src/screens/setup/GoogleStep.tsx`
- Create: `web-ui/src/screens/setup/PasswordStep.tsx`
- Create: `web-ui/src/screens/setup/InitialResolutionStep.tsx`
- Create: `web-ui/src/screens/setup/SetupWizard.tsx`
- Test: `web-ui/tests/setup-wizard.test.tsx`

**Interfaces:**
- Consumes: `DaemonApiClient`, `store.ts`.
- Produces: Полнофункциональный визард первичной настройки.

- [ ] **Step 1: Write wizard flow test**

Тест пошагового прохождения мастера: `VaultStep` (/vault) -> `GoogleStep` (Browser / Device) -> `PasswordStep` -> `InitialResolutionStep` (подтверждение слияния).

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: FAIL.

- [ ] **Step 3: Implement setup wizard screens**

- `VaultStep.tsx`: подтверждение пути тома `/vault` с вызовом `configureVault("/vault")`.
- `GoogleStep.tsx`: выбор Browser OAuth или Device Code Flow. При Browser: открытие ссылки в новом окне и поллинг `pollBrowserLogin()`. При Device: показ кода и ссылки `google.com/device`.
- `PasswordStep.tsx`: задание пароля шифрования с чекбоксом «Запомнить на этом устройстве».
- `InitialResolutionStep.tsx`: вызов `inspectVault()`, вывод плашки с количеством локальных и удаленных файлов, кнопка «Начать синхронизацию» (`triggerSync()`).
- `SetupWizard.tsx`: координатор переключения шагов.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/screens/setup/ web-ui/tests/setup-wizard.test.tsx
git commit -m "feat(web-ui): implement 4-step setup wizard matching desktop flow"
```

---

### Task 6: Monitoring, Unlock & Conflict Screens and App Routing

**Files:**
- Create: `web-ui/src/screens/StatusScreen.tsx`
- Create: `web-ui/src/screens/UnlockScreen.tsx`
- Create: `web-ui/src/screens/ConflictsScreen.tsx`
- Create: `web-ui/src/App.tsx`
- Create: `web-ui/src/main.tsx`
- Test: `web-ui/tests/app-routing.test.tsx`

**Interfaces:**
- Consumes: `store.ts`, `screens/*`.
- Produces: Корневое приложение Preact SPA, реагирующее на смену состояния демона.

- [ ] **Step 1: Write App routing test**

Тест автоматического выбора экрана:
- `vaultPath == null` -> отображается `SetupWizard`.
- `isLocked == true` -> отображается `UnlockScreen`.
- `conflicts.length > 0` и открыта вкладка конфликтов -> `ConflictsScreen`.
- Обычное состояние -> `StatusScreen`.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: FAIL.

- [ ] **Step 3: Implement screens and App.tsx**

- `StatusScreen.tsx`: карточка статуса, круговой прогресс, счетчик файлов/байтов, кнопка `Sync Now`, плашка предупреждения о конфликтах с переходом.
- `UnlockScreen.tsx`: поле ввода пароля, чекбокс `Запомнить`, обработка ошибок неверного пароля.
- `ConflictsScreen.tsx`: список конфликтов с кнопками `Keep Local` и `Keep Remote`.
- `App.tsx`: корневая маршрутизация на основе сигналов хранилища.
- `main.tsx`: монтирование `render(<App />, document.getElementById("app")!)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk pnpm --filter @mimir/web-ui test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/screens/ web-ui/src/App.tsx web-ui/src/main.tsx web-ui/tests/
git commit -m "feat(web-ui): implement status, unlock, conflicts screens and root app"
```

---

### Task 7: Gradle Build Integration & Docker Packaging

**Files:**
- Create: `web-ui/build.gradle.kts`
- Modify: `daemon/build.gradle.kts:55-65`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt:85-105`
- Modify: `packaging/docker/Dockerfile:10-20`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/WebUiServedTest.kt`

**Interfaces:**
- Consumes: `web-ui/dist/`.
- Produces: раздача статики SPA Ktor-демоном из `resources/static`, включение сборки в Docker-образ.

- [ ] **Step 1: Write static serving and SPA fallback integration test**

В `WebUiServedTest.kt`:
- Проверка ответа `GET /`: `200 OK`, `text/html`.
- Проверка ответа `GET /some/spa/route`: `200 OK`, `text/html` (SPA fallback).
- Проверка отдачи статических JS/CSS с правильными `Content-Type`.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :daemon:test --tests "*WebUiServedTest*"`
Expected: FAIL (маршрут fallback не настроен).

- [ ] **Step 3: Configure build.gradle.kts and Routes.kt**

В `web-ui/build.gradle.kts`:
Задача `buildWebUi` запускает `pnpm run build`. Если `pnpm` не найден, создает `dist/index.html` заглушку.
В `daemon/build.gradle.kts`:
`tasks.processResources { from(project(":web-ui").file("dist")) { into("static") } }`
В `Routes.kt`:
Статический роутинг с fallback на `index.html` при 404 для всех не-API маршрутов.
В `packaging/docker/Dockerfile`:
Установка pnpm и сборка Web UI в stage `build`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk ./gradlew :daemon:test --tests "*WebUiServedTest*" && rtk mise run build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/build.gradle.kts daemon/build.gradle.kts daemon/src/main/ packaging/docker/Dockerfile daemon/src/test/
git commit -m "feat(daemon): bundle preact web-ui into static resources with spa fallback"
```

---

## Plan Review & Handoff

План реализации полностью сформирован и зафиксирован в [docs/superpowers/plans/2026-11-03-web-ui-docker.md](file:///home/gidragir/projects/mimir/docs/superpowers/plans/2026-11-03-web-ui-docker.md).

Для исполнения этого плана рекомендуется подход **Subagent-driven**, так как задачи разделены на независимые слои (Backend DTO -> UI Scaffolding -> API Client -> Components -> Screens -> Packaging), каждый из которых изолированно покрывается юнит- и интеграционными тестами.

Какой метод выполнения плана вы предпочитаете?
1. **Subagent-driven** (Рекомендуемый): запуск специализированных субагентов для каждой задачи с независимым код-ревью между шагами.
2. **Native**: пошаговое выполнение всех задач в текущей сессии.
