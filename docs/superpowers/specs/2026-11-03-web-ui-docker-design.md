# Спецификация: Легковесный Web UI для Docker-контейнера Mimir (Preact v11.1.1 + Vite SPA)

**Дата:** 2026-11-03  
**Статус:** Ready for Approval  
**Область:** `web-ui`, `daemon`, `runtime`, `client`, `packaging/docker`  

---

## 1. Контекст и мотивация (Intent & The Why)

Mimir поддерживает запуск демона синхронизации в Docker-контейнере (`packaging/docker/Dockerfile`). В headless-режиме у контейнера нет собственного графического дисплея, поэтому веб-интерфейс является основным окном управления: первоначальная настройка (выбор хранилища, авторизация Google Drive, мастер-пароль, первичное слияние), мониторинг статуса синхронизации и разрешение конфликтов версий файлов.

Вариант на Compose Multiplatform (Wasm) был отклонен из-за тяжеловесности: размер бандла составляет 15–20 МБ, Canvas-рендеринг потребляет свыше 150 МБ RAM, а холодный старт занимает 1.5–3 секунды.

Цель данной спецификации — разработка **сверхлегковесного (бандл < 45 КБ gzip), быстрого (< 50 мс cold start) веб-приложения (SPA)** на стеке **Preact v11.1.1 + Vite + TypeScript + Tailwind CSS v4**, которое **строго повторяет экраны, стилистику Obsidian и функционал десктопного приложения Mimir**, включая стандартную браузерную OAuth-авторизацию при локальном запуске контейнера с `--network=host`.

---

## 2. Цели, не-цели и анти-цели

### Цели (Goals)
1. **Экстремальная легковесность и скорость:** Суммарный размер дистрибутива в gzip < 45 КБ, мгновенный первый рендер (< 50 мс), потребление памяти в браузере < 25 МБ.
2. **Визуальная идентичность с Desktop UI:** Стилистика Obsidian / Linear Craft с использованием единого источника токенов `design/tokens.json` через сгенерированный `tokens.css`.
3. **100% покрытие функционала Desktop:**
   - **Мастер первичной настройки (`SetupWizard`):**
     1. `VaultStep`: выбор/подтверждение смонтированного тома `/vault`.
     2. `GoogleStep`: выбор метода входа (Browser OAuth / Device Code Flow) на основе ответа демона `GET /api/v1/auth/methods`.
     3. `PasswordStep`: задание мастер-пароля шифрования с опцией «Запомнить на этом устройстве».
     4. `InitialResolutionStep`: аудит первичного состояния через `GET /api/v1/vault/inspect` и подтверждение слияния локальных и удаленных файлов со снятием удержания (`triggerSync()`).
   - **Экран заблокированного состояния (`UnlockScreen`):** ввод мастер-пароля для разблокировки хранилища.
   - **Экран мониторинга (`StatusScreen`):** статус синхронизации (Idle, Syncing, Offline, Error), анимированный круговой прогресс-бар, количество обработанных файлов/байтов, время последней синхронизации, кнопка принудительного запуска `Sync Now`.
   - **Разрешение конфликтов (`ConflictsScreen`):** список версий файлов, выбор стратегии (`Keep Local`, `Keep Remote`).
4. **Стандартная браузерная OAuth-авторизация в Docker:** Поддержка входа через Desktop OAuth Client (PKCE + loopback callback `127.0.0.1:<port>`) при запуске контейнера с параметром `--network=host`. Альтернативный метод: Device Code Flow (для удаленного доступа).
5. **Адаптация под Docker-окружение:**
   - Автоматический выбор пути `/vault` (без системного файлового диалога ОС).
   - Персистентность мастер-пароля: расширение `POST /api/v1/unlock` полем `remember: Boolean = false`, сохраняющим пароль в `configDir/password` демона с правами `0600`.
   - Поддержка токена доступа к API (`MIMIR_TOKEN`) через параметр URL (`?token=...`) или `localStorage`.
6. **Автоматическая интеграция в сборку Gradle:** Сборка `pnpm --filter @mimir/web-ui build` интегрирована в Gradle-задачу `:daemon:processResources`, артефакты `dist/` упаковываются в `static/`.

### Не-цели (Non-Goals)
1. Встроенный редактор заметок или Markdown-вьюер (в v1 Web UI выступает административной панелью управления синхронизацией).
2. Поддержка браузеров без поддержки ES2022 / Fetch API.
3. Проброс произвольных директорий хоста вне смонтированного тома `/vault`.

### Анти-цели (Anti-Goals)
1. Раздувание бандла тяжелыми иконочными библиотеками — только компактные inline SVG.
2. Использование тяжелых сторонних компонентов (MUI, Ant Design).
3. Поломка сборки Gradle в окружениях без Node.js/pnpm: при отсутствии pnpm создается минимальная заглушка `index.html`.

---

## 3. Ограничения (Constraints)

1. **Docker Host Network:** Для работы стандартной браузерной авторизации контейнер запускается с флагом `--network=host`.
2. **Безопасность учетных данных Google:** Согласно `AGENTS.md`, секреты Google OAuth не вшиваются в образ и передаются через `-e MIMIR_GOOGLE_DESKTOP_CLIENT_ID` и `-e MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET`.
3. **CI и пакетные менеджеры:** Модуль `web-ui` регистрируется в `pnpm-workspace.yaml` и управляется pnpm.

---

## 4. Архитектурные подходы (Approaches)

### Сравнение альтернатив

| Подход | Вес бандла (gzip) | Холодный старт | UX и реактивность | Вердикт |
| :--- | :--- | :--- | :--- | :--- |
| **Compose Multiplatform Wasm** | 15–20 МБ | 1.5–3.5 с | Высокая, но Canvas/WebGL оверхед | Отклонен из-за тяжеловесности |
| **Ktor SSR + HTMX** | 15–25 КБ | < 30 мс | Скачкообразные переходы в мастере | Отклонен: хуже плавность и анимации |
| **Preact v11.1.1 + Vite SPA** | **~35–45 КБ** | **< 50 мс** | **Плавный SPA, UDF, реактивность** | **Выбран пользователем** |

---

## 5. Детальный дизайн реализации

### 5.1 Структура модуля `web-ui/`

```
web-ui/
├── package.json               # @mimir/web-ui (Preact v11.1.1, @preact/signals, Vite, Tailwind v4)
├── tsconfig.json
├── vite.config.ts             # Сборка в dist/, базовый путь "./"
├── build.gradle.kts           # Gradle Exec таска: pnpm build -> dist/
├── index.html                 # Контейнер #app, темная тема по умолчанию
└── src/
    ├── main.tsx               # Точка монтирования Preact приложения
    ├── App.tsx                # Корневой роутер экранов (Setup, Status, Conflicts, Unlock)
    ├── api/
    │   ├── types.ts           # DTO-типы, зеркалирующие DaemonDto.kt
    │   └── client.ts          # Fetch-клиент к REST API демона (/api/v1/*)
    ├── state/
    │   └── store.ts           # Реактивные сигналы (@preact/signals): status, progress, conflicts
    ├── styles/
    │   ├── tokens.css         # Сгенерированные дизайн-токены (design/tokens.json)
    │   └── app.css            # Tailwind v4 (@import "tailwindcss"; @layer theme)
    ├── components/
    │   ├── Button.tsx         # Кнопки Linear/Obsidian style (Primary, Secondary, Danger)
    │   ├── Input.tsx          # Поля ввода паролей и путей
    │   ├── CircularProgress.tsx # Кольцевой индикатор синхронизации
    │   ├── Card.tsx           # Стилизованные карточки
    │   └── Icons.tsx          # Компактные inline SVG иконки
    └── screens/
        ├── StatusScreen.tsx   # Статус синхронизации, прогресс, кнопка Sync
        ├── UnlockScreen.tsx   # Ввод мастер-пароля
        ├── ConflictsScreen.tsx # Разрешение версионных конфликтов
        └── setup/
            ├── SetupWizard.tsx          # Оркестратор шагов визарда
            ├── VaultStep.tsx            # Подтверждение /vault
            ├── GoogleStep.tsx           # Выбор и запуск Browser OAuth / Device Code
            ├── PasswordStep.tsx         # Задание пароля шифрования
            └── InitialResolutionStep.tsx # Аудит inspectVault и подтверждение слияния
```

### 5.2 Поток авторизации (Browser OAuth & Device Code в Docker)

1. Экран `GoogleStep.tsx` запрашивает доступные методы через `GET /api/v1/auth/methods`.
2. Если возвращается `Browser`:
   - При нажатии «Войти через Google» Web UI вызывает `POST /api/v1/auth/browser/start`.
   - Демон запускает `BrowserLoginReceiver` на `127.0.0.1:<random_port>` и возвращает URL.
   - Web UI открывает Google Auth URL в новой вкладке через `window.open(url, "_blank")`.
   - Google перенаправляет браузер на `http://127.0.0.1:<random_port>/callback`.
   - Благодаря флагу `--network=host`, запрос попадает в `BrowserLoginReceiver`.
   - Страница успеха выводит «Вход выполнен успешно!» и закрывает вкладку (`window.close()`).
   - Web UI в исходной вкладке опрашивает `POST /api/v1/auth/browser/poll`, получает `Success` и переходит к `PasswordStep`.
3. Если возвращается `DeviceCode` (или пользователь переключился на альтернативный вход):
   - Web UI вызывает `POST /api/v1/auth/device/start`, отображает PIN-код и ссылку `google.com/device`.
   - Запускается поллинг `POST /api/v1/auth/device/poll` до подтверждения.

### 5.3 Контракт сохранения пароля и персистентности

В `client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt`:
```kotlin
@Serializable
data class DaemonUnlockRequest(
    val password: String,
    val remember: Boolean = false,
)
```
- Если `remember == true`, демон сохраняет открытый мастер-пароль в файл `$configDir/password` с правами доступа `0600`.
- При старте `DaemonRuntime` проверяет наличие этого файла и автоматически разблокирует хранилище.

### 5.4 Раздача статики в Ktor CIO (`:daemon`)

В `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`:
```kotlin
staticResources("/", "static") {
    default("index.html")
}
statusPage { status ->
    if (status == HttpStatusCode.NotFound && !call.request.path().startsWith("/api")) {
        call.respondText(
            this::class.java.classLoader.getResource("static/index.html")?.readText() ?: "",
            ContentType.Text.Html
        )
    }
}
```

### 5.5 Сборка и интеграция в Dockerfile

1. `packaging/docker/Dockerfile`:
   - В stage `build`: установка pnpm (`RUN corepack enable && corepack prepare pnpm@latest --activate`).
   - Сборка Web UI перед упаковкой демона: `RUN pnpm --filter @mimir/web-ui build`.
2. `web-ui/build.gradle.kts`:
   - Выполняет сборку через `pnpm build`, если Node/pnpm установлены в системе.
   - Если pnpm отсутствует, проверяет наличие существующего каталога `dist/`. Если каталог отсутствует — генерирует минимальный `dist/index.html` для предотвращения падения сборки Gradle.

---

## 6. Инварианты Mimir (Mimir Invariants)

### 1. Платформы (Cross-Platform)
- **Docker / Linux:** Демон работает headless в контейнере. Браузер на хосте управляет синхронизацией.
- **Desktop / Android / Extension:** Сохраняют свои существующие реализации, дизайн-токены общие (`design/tokens.json`).

### 2. Дизайн (Design Patterns)
- **Паттерн:** Single Page Application (SPA) с Unidirectional Data Flow (UDF) на базе `@preact/signals`.
- **Компонентная модель:** Презентационные «глупые» компоненты (`Card`, `Button`, `Input`) + контейнеры экранов (`StatusScreen`, `SetupWizard`).

### 3. IO (Sync & Network IO)
- Нагрузка Web UI на демон минимальна (HTTP REST JSON, периодический поллинг 0.5–3 с).
- Никаких прямых обращений из браузера к Google Drive: взаимодействие с облаком идет строго через ядро демона.

### 4. UX (States & Texts)
- Тема Obsidian: строгая темная палитра (`#1e1e1e`, `#161616`, акцент `#705dcf`), радиус скруглений 6-8px, системные monospace и sans-serif шрифты.
- Полная локализация и текстовые константы, идентичные десктопу (`SyncTexts`).

---

## 7. Решения архитектора (Architect's Calls)

1. **Единый контракт персистентности пароля:** Поле `remember: Boolean` добавлено непосредственно в тело запроса `POST /api/v1/unlock` (`DaemonUnlockRequest`), исключая создание избыточных эндпоинтов. Сохранение производится в файл конфигурации `$configDir/password` демона.
2. **Шаг первичного аудита:** В `SetupWizard.tsx` обязательно включен `InitialResolutionStep.tsx`, повторяющий десктопную проверку `inspectVault()` и снимающий `holdInitialSync` вызовом `triggerSync()`.
3. **Безопасная сборка без pnpm:** В `web-ui/build.gradle.kts` предусмотрена генерация заглушки `dist/index.html` при отсутствии локального Node.js, что гарантирует прохождение `./gradlew build` без сторонних зависимостей.
