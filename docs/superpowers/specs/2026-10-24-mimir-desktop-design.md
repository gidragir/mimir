# Mimir — этап 5: десктопное приложение (`app-desktop` и `ui`)

Дата: 2026-10-24  
Статус: согласовано (Builder-Checked)  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md` (§6.1, §6.2, §6.4, §6.7, §7). При конфликте приоритет у родительской спеки.  
Спека этапа 4: `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md`

---

## 1. Замысел и цели

Этапы 1–4 сформировали ядро Mimir: криптографический слой `RcloneCipher`, движок трехсторонней сверки `DefaultSyncEngine`, Google Drive адаптер с Device Code Grant, локальное хранилище `FileSystemLocalVault` с отслеживанием изменений через `VaultWatcher`, фоновый оркестратор `DaemonRuntime` и Ktor HTTP API v1.

Этап 5 превращает Mimir в полноценное десктопное приложение для Linux (Arch / CachyOS с Wayland niri и X11) и Windows:
1. **Полноценный графический интерфейс (Compose Multiplatform в `ui`)**:
   - Отображение текущего состояния (строго `"Mimir: not connected"` до настройки, `"Mimir: idle"`, `"Mimir: syncing"`).
   - Индикация времени последней синхронизации, email подключенного Google-аккаунта, количества неразрешенных конфликтов.
   - Мастер первичной настройки (First-Run Wizard): выбор папки хранилища (vault), вход через Google Device Code Flow, ввод и верификация мастер-пароля шифрования.
   - Центр разрешения конфликтов версий файлов (`conflicts`): просмотр обнаруженных дубликатов, выбор «Оставить оригинал» (`keep_original`) или «Оставить копию» (`keep_conflict`).
2. **Гибридная интеграция рантайма (`app-desktop`)**:
   - Работа in-process по умолчанию: прямое владение `DaemonRuntime` с реактивными `StateFlow`, без задержек и HTTP-оверхеда для GUI.
   - Параллельный запуск Ktor HTTP API на `127.0.0.1:47821` внутри десктопного процесса, обеспечивающий прозрачный доступ для локального плагина Obsidian (`obsidian-plugin`).
   - Прозрачный fallback на `HttpDaemonClient`: если порт `47821` уже занят работающим системным демоном Mimir, приложение автоматически переключается в режим GUI-клиента поверх HTTP API.
3. **Системный трей под Wayland (niri / StatusNotifierItem) и Windows (AWT)**:
   - Linux: нативная регистрация `org.kde.StatusNotifierItem` через Session D-Bus (`dbus-java-transport-native-unixsocket` поверх Java 16+ Unix Domain Sockets) для отображения в status bar (noctalia, waybar).
   - Windows: интеграция через `java.awt.SystemTray`.
   - Действия контекстного меню: «Открыть» (показать окно), «Синхронизировать сейчас» (Sync now), «Выход» (Quit).
   - Поведение при закрытии: сворачивание в трей при наличии доступного трея; завершение работы приложения, если трей недоступен.
4. **Закрытие технических долгов (Carry-over из этапа 4)**:
   - Canary check при `unlock()`: предотвращение создания дублирующего шифрованного пространства при вводе неверного пароля.
   - Динамическая настройка `vaultPath`: сохранение выбранного пути в `config.json` и пересборка графа `DaemonRuntime`.
   - Кэширование и реактивный подсчет активных конфликтов без полного обхода vault при каждом запросе статуса.
   - Проверка прав доступа `passwordFile` (POSIX 0600 на Unix).
   - Исправление тайминга `markWrittenBySync` (регистрация пути строго после успешного `atomicMove`).

---

## 2. Цели, не-цели и анти-цели

### 2.1 Цели
- **Модуль `ui` (Compose Multiplatform)**:
  - Компонент `MimirApp`, управляющий переключением экранов: `StatusScreen`, `SetupWizardScreen`, `ConflictsScreen`.
  - Модель представления (`MimirUiModel`), изолирующая `ui` от платформенного рантайма и сохраняющая совместимость со всеми таргетами KMP (`desktop`, `android`, `wasmJs`).
  - Экран `StatusScreen`: статус синхронизации (`label`), кнопка немедленного запуска «Синхронизировать», бейдж конфликтов с переходом в менеджер конфликтов, баннер разблокировки/авторизации при их отсутствии.
  - Экран `SetupWizardScreen`: пошаговый мастер первичной настройки с валидацией локального пути, ссылкой и кодом авторизации Google (`verification_url`, `user_code`) и разблокировкой шифрования.
  - Экран `ConflictsScreen`: список конфликтов с метаданными (оригинальный путь, дата создания) и кнопками разрешения.
- **Модуль `app-desktop` (Точка входа и платформенная интеграция)**:
  - Интеграция системного трея `Tray`:
    - `DbusStatusNotifierTray` для Linux (чистый JVM D-Bus через `SocketChannel` без нативных C-библиотек).
    - `AwtTray` для Windows.
    - `NoTray` как безопасный fallback при отсутствии трей-сервиса на шине.
  - Управление жизненным циклом: запуск/остановка `DaemonRuntime`, Ktor сервера, закрытие окна в трей (`visible = false`), выход из трея.
  - Адаптер `DaemonServiceUiModel`, связывающий `DaemonService` и `MimirUiModel`.
- **Расширение `DaemonService` и `DaemonRuntime`**:
  - Метод `configureVault(vaultPath: String)` с атомарной записью в `~/.config/mimir/config.json`.
  - Метод `unlock(password: String)` с проверкой canary-файла.
  - Реактивное состояние `conflicts: StateFlow<List<ConflictFileEntry>>`.
  - Эндпоинт `POST /api/v1/vault` в `Routes.kt`.

### 2.2 Не-цели
- Мобильный интерфейс и сервисы Android (Этап 6, `app-android`).
- Плагин для Obsidian (Этап 7, `obsidian-plugin`).
- Браузерное расширение (Этап 8, `browser-extension`).
- Встроенный визуальный side-by-side текстовый редактор разницы (diff) заметок внутри приложения (разрешение конфликтов производится на уровне выбора файла; слияние содержимого заметок пользователь делает в Obsidian).

### 2.3 Анти-цели
- Блокировка UI-потока Compose при синхронизации, сетевых запросах к Google Drive или обходе файловой системы.
- Молчаливое создание дублирующего шифрованного дерева заметок в Google Drive при опечатке в мастер-пароле.
- Падение десктопного приложения с ошибкой `Port already in use`, если пользователь параллельно запустил фоновый демон через systemd.
- Включение внешних C-библиотек (JNI/JNA), ломающих сборку и запуск на JDK 27 или требующих ручной установки пакетов пользователем.
- Нарушение инварианта направленности зависимостей из `AGENTS.md` (модуль `ui` ни при каких обстоятельствах не зависит от `daemon`).

---

## 3. Ограничения

1. **Среда разработки**: Arch Linux (bash, JDK 27, niri Wayland). Все сборки и тесты выполняются через `mise run <задача>`.
2. **Сборка без секретов и SDK**: `mise run build` и `mise run test` обязаны проходить без установленного Android SDK и без секретов в `mise.local.toml`.
3. **Строгие идентификаторы**: Версии зависимостей — строго в `gradle/libs.versions.toml`, параметры приложения — в `gradle.properties` (`mimir.appName`, `mimir.version`, `mimir.apiDefaultPort` и др.).
4. **Контракт статуса**: До первичной настройки и подключения статус обязан возвращать ровно `"Mimir: not connected"` (`SyncStatus.NotConfigured.label()`).

---

## 4. Архитектурные решения (Approaches & Tradeoffs)

### 4.1 Связка UI и рантайма: Гибридный In-Process с Fallback на HTTP

| Подход | Преимущества | Недостатки | Решение |
| :--- | :--- | :--- | :--- |
| **A. Только In-Process** | Нулевой оверхед, реактивные `StateFlow`, мгновенный отклик | Конфликт за порт `47821`, если демон уже запущен в системе | Отклонен из-за риска сбоя |
| **B. Только HTTP-клиент** | Полная изоляция | Необходимость поллинга, задержки UI, необходимость спавнить процесс демона | Отклонен из-за деградации UX |
| **C. Гибрид (In-Process по умолчанию + HTTP fallback)** | Прямое реактивное взаимодействие; Ktor API доступен для Obsidian; прозрачное подключение к работающему системному демону | Необходимость реализации `HttpDaemonClient` адаптера | **Принят как Best Practice** |

#### Архитектура модулей и зависимостей (строгое следование AGENTS.md):
```
+---------------------------------------------------------------------------------+
|                                  app-desktop                                    |
|               (JVM Entrypoint: Main, Tray, Window, Lifecycle)                   |
|                   ├── implementation(project(":ui"))                            |
|                   └── implementation(project(":daemon"))                        |
+----------------------------------------+----------------------------------------+
                                         |
                     +-------------------+-------------------+
                     |                                       |
                     v                                       v
+-----------------------------------------+   +-----------------------------------+
|                   ui                    |   |              daemon               |
|      (KMP: desktop, android, wasmJs)    |   |     (JVM: Runtime, HTTP Server)   |
|         ├── MimirApp                    |   |         ├── DaemonRuntime         |
|         ├── MimirUiModel (interface)    |   |         ├── DaemonService (iface) |
|         └── implementation(":core")     |   |         ├── mimirRoutes           |
+--------------------+--------------------+   |         └── implementation(":drive|
                     |                        +-----------------+-----------------+
                     |                                          |
                     +-------------------+  +-------------------+
                                         |  |
                                         v  v
+---------------------------------------------------------------------------------+
|                                     core                                        |
|                     (Pure KMP: SyncEngine, Cipher, Vault)                       |
+---------------------------------------------------------------------------------+
```
*Правило чистоты графа*:
- Модуль `:ui` **не зависит** от `:daemon`. `ui` определяет интерфейс `MimirUiModel`.
- Модуль `:app-desktop` связывает обе части: инстанциирует `DaemonRuntime` (или `HttpDaemonClient`) и оборачивает его в `DaemonServiceUiModel : MimirUiModel` для передачи в `MimirApp`.

### 4.2 Системный трей под Linux Wayland и Windows

| Подход | Преимущества | Недостатки | Решение |
| :--- | :--- | :--- | :--- |
| **AWT `SystemTray`** | Встроен в JDK | Не работает в Wayland (niri, Sway) без XEmbed; `isSupported()` возвращает `false` | Используется только на Windows |
| **JNA / `libayatana-appindicator`** | Системный AppIndicator | Требует динамические библиотеки Си, флаги доступа к памяти в JDK 27 | Отклонен |
| **Чистый D-Bus (`dbus-java-transport-native-unixsocket`)** | 100% чистый Java 16+ (`SocketChannel`), zero native code, прямая поддержка `org.kde.StatusNotifierItem` под Wayland | Требует описание интерфейсов D-Bus | **Принят как Best Practice** |

---

## 5. Детальный дизайн компонентов

### 5.1 Canary Check для защиты от неверного пароля шифрования

В схеме `rclone crypt` расшифровка случайных байт с неверным паролем не вызывает ошибку аутентификации блока, а приводит к повреждению данных или невидимости зашифрованных файлов.
Для надежной валидации внедряется контрольный файл-канарейка:

#### Хранение и формат:
1. **Имя файла**: незашифрованное имя `.mimir_canary` в корневом каталоге удаленного хранилища (`inner: RemoteStorage`, папка `MimirVault`). Открытое имя файла в корне облака гарантирует, что `unlock(password)` ищет один и тот же файл независимо от вводимого пароля.
2. **Содержимое файла**: контрольный блок, зашифрованный через `cipher.encrypt(Source)`.
   - Открытый текст: `{"magic":"mimir-vault-canary","version":1}` (UTF-8).
   - Зашифрованный текст: стандартный формат rclone (заголовок `RCLONE\x00\x00` + 24 байта nonce + Poly1305 MAC + шифротекст).

#### Алгоритм `unlock(password)`:
1. Инициализируется кандидат `testCipher = RcloneCipher(password)`.
2. Если удаленное хранилище `remoteStorage` настроено:
   - Проверяется существование файла `.mimir_canary` в `remoteStorage`.
   - **Случай А: `.mimir_canary` существует**:
     - Читается содержимое файла через `remoteStorage.read(".mimir_canary")`.
     - Производится попытка расшифровки через `testCipher.decrypt(source)`.
     - Если `SecretBox.open` бросает `CryptException` (сбой проверки Poly1305 MAC) или magic не равен `"mimir-vault-canary"`:
       -> Выбрасывается `InvalidPasswordException("Invalid encryption password")`.
     - При успешной проверке пароль принимается, создается `cipher = testCipher`, локально кэшируется хеш canary.
   - **Случай Б: `.mimir_canary` отсутствует**:
     - Запрашивается список файлов `remoteStorage.list()`.
     - Если в облачном хранилище уже есть другие файлы:
       -> Выбрасывается `InvalidPasswordException("Remote vault contains existing encrypted data but canary is missing")`.
     - Если облачное хранилище пусто (первичная инициализация нового vault):
       - Шифруется контрольный JSON через `testCipher.encrypt` и сохраняется в `remoteStorage.write(".mimir_canary", ...)`.
       - Пароль принимается, создается `cipher = testCipher`.
3. **Офлайн-режим (Offline First)**:
   - Если сеть недоступна и `remoteStorage` бросает сетевое исключение:
     - Проверяется локальный файл `.canary_hash` в `~/.config/mimir/` (содержащий `HMAC-SHA256(password, salt)`).
     - При совпадении хэша пароль принимается для работы с локальным vault.
     - Если `.canary_hash` отсутствует (первый запуск): разблокировка отклоняется с сообщением о необходимости подключения к сети для первичной верификации.

### 5.2 Динамическая настройка Vault и сохранение `config.json`

В `DaemonConfig` добавляется метод сохранения:
```kotlin
fun save(configDir: String? = null)
```
Запись производится атомарно во временный файл с правами 0600.
В `DaemonService` и `DaemonRuntime` добавляется метод:
```kotlin
override suspend fun configureVault(vaultPath: String)
```
Метод валидирует существование локального каталога, сохраняет обновленный путь в `config.json` и инициирует `rebuild()`.
В HTTP API `daemon` добавляется эндпоинт `POST /api/v1/vault` с телом `{"vaultPath": "..."}`.

### 5.3 Реактивный кэш конфликтов

Вместо сканирования всего диска при каждом вызове статуса:
- `DaemonRuntime` поддерживает `_conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())`.
- При инициализации и при событиях модификации файловой системы от `VaultWatcher` список конфликтов обновляется в фоне с дебаунсом.
- Метод `conflicts(): List<ConflictFileEntry>` и свойство `activeConflicts` в статусе обращаются к закэшированному снимку в памяти.

### 5.4 Реализация StatusNotifierItem на Linux через D-Bus

Модуль `app-desktop` на Linux подключается к сессионной шине D-Bus:
1. Зависимости в `app-desktop/build.gradle.kts`:
   - `com.github.hypfvieh:dbus-java-core:5.2.2`
   - `com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2`
2. Регистрация на шине сервиса: `org.freedesktop.StatusNotifierItem-<PID>-1` по пути `/StatusNotifierItem`.
3. Реализация свойств `org.kde.StatusNotifierItem`:
   - `Category`: `"ApplicationStatus"`
   - `Id`: `"mimir"`
   - `Title`: `"Mimir"`
   - `Status`: `"Active"`
   - `IconName`: `"mimir"` (или `"mimir-syncing"`, `"mimir-error"`)
   - `ToolTip`: struct `("mimir", [], "Mimir", statusText)`
4. Вызов `org.kde.StatusNotifierWatcher.RegisterStatusNotifierItem("/StatusNotifierItem")`.
5. Обработка сигналов/методов:
   - `Activate(x, y)`: вызов `onOpen()` (разворачивание главного окна).
   - `ContextMenu(x, y)`: меню с пунктами «Открыть», «Синхронизировать сейчас», «Выход».

### 5.5 Интерфейс пользователя (Compose UI) и `MimirUiModel`

Интерфейс UI-модели в `ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt`:
```kotlin
package dev.mimir.ui.model

import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

data class UiConflictEntry(val originalPath: String, val conflictPath: String, val createdAt: Instant)
data class UiDeviceCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int)

sealed interface UiDevicePoll {
    data object Pending : UiDevicePoll
    data class Authorized(val email: String?) : UiDevicePoll
    data class Expired(val reason: String) : UiDevicePoll
}

interface MimirUiModel {
    val status: StateFlow<SyncStatus>
    val vaultPath: StateFlow<String?>
    val isLocked: StateFlow<Boolean>
    val lastSyncTime: StateFlow<Instant?>
    val accountEmail: StateFlow<String?>
    val conflicts: StateFlow<List<UiConflictEntry>>

    suspend fun triggerSync(): Boolean
    suspend fun configureVault(path: String)
    suspend fun unlock(password: String, rememberOnDevice: Boolean = true)
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
    suspend fun startDeviceLogin(): UiDeviceCode
    suspend fun pollDeviceLogin(deviceCode: String): UiDevicePoll
}
```

1. **`MimirApp`**:
   - Верхняя панель: название приложения `BuildConfig.APP_NAME`, индикатор статуса, email аккаунта (если авторизован).
   - Навигационная вкладка / переключатель экранов: «Статус», «Конфликты» (с бейджем счетчика), «Настройки».
2. **`StatusScreen`**:
   - Карточка текущего состояния: большая пиктограмма статуса, текстовая метка (`SyncStatus.label()`), время последней синхронизации.
   - Кнопка «Синхронизировать сейчас» (активна только в статусе `Idle`).
   - Предупреждающий баннер при наличии конфликтов с кнопкой перехода в `ConflictsScreen`.
   - Баннер первого запуска, если хранилище или шифрование не настроены.
3. **`SetupWizardScreen`**:
   - Шаг 1: Путь к Obsidian Vault (текстовое поле + кнопка выбора директории).
   - Шаг 2: Google Авторизация (Device Code Flow: отображение URL `https://www.google.com/device`, выделенный крупный код подтверждения, кнопка «Скопировать код и открыть ссылку в браузере», индикатор ожидания завершения авторизации).
   - Шаг 3: Мастер-пароль шифрования (поле ввода пароля с маскированием, чекбокс «Запомнить пароль на этом устройстве», кнопка разблокировки/сохранения, индикатор валидации через canary check).
4. **`ConflictsScreen`**:
   - Список неразрешенных файлов конфликтов:
     - Оригинальный путь (`Notes/Design.md`)
     - Дата и время создания конфликта
   - Действия для каждой строки:
     - «Оставить оригинал» -> `resolveConflict(..., keepConflict = false)`
     - «Оставить копию» -> `resolveConflict(..., keepConflict = true)`

---

## 6. План тестирования (TDD)

1. **Модульные тесты `ui` (`desktopTest`)**:
   - `StatusScreenTest`: отображение точного статуса `"Mimir: not connected"`, статуса синхронизации, времени последней синхронизации, бейджа конфликтов.
   - `ConflictsScreenTest`: рендеринг списка конфликтов, эмуляция клика «Оставить оригинал» и «Оставить копию» с проверкой вызова методов сервиса.
   - `SetupWizardScreenTest`: проверка переходов между шагами мастера, валидация полей ввода, отображение device code.
2. **Тесты рантайма `daemon` (`test`)**:
   - `CanaryCheckTest`: успешная разблокировка с верным паролем, ошибка `InvalidPasswordException` с неверным паролем, создание canary для нового хранилища.
   - `ConfigureVaultTest`: динамическая установка `vaultPath`, сохранение в `config.json` и инициализация `FileSystemLocalVault`.
   - `ConflictCacheTest`: инкрементальное обновление списка конфликтов при файловых изменениях.
3. **Интеграционные тесты `app-desktop` (`test`)**:
   - `TrayLifecycleTest`: создание и закрытие трея, эмуляция `onOpen`, `onSyncNow`, `onQuit`.
   - `DaemonServiceFactoryTest`: выбор между in-process и HTTP клиентом в зависимости от доступности порта.
