# Mimir — этап 4: локальное хранилище Vault, фоновый демон и локальный HTTP API (Local Vault, Daemon Engine & HTTP API)

Дата: 2026-10-24  
Статус: согласовано (Builder-Checked)  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md` (§6.1, §6.2, §6.2a, §6.3, §6.4, §6.5, §6.7). При конфликте приоритет у родительской спеки.  
Спеки предыдущих этапов:
- Этап 1 (шифрование): `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md`
- Этап 2 (движок синхронизации): `docs/superpowers/specs/2026-10-23-mimir-sync-design.md`
- Этап 3 (Google Drive и OAuth): `docs/superpowers/specs/2026-10-23-mimir-drive-design.md`

---

## 1. Замысел и цели

Этапы 1–3 предоставили криптографический слой `RcloneCipher` с шифрованием имен и содержимого, чистый KMP-движок синхронизации `DefaultSyncEngine` с трехсторонней сверкой и разрешением конфликтов, а также Ktor-адаптер Google Drive API с поддержкой Changes Feed и Device Code Grant.

Этап 4 объединяет эти изолированные компоненты в полноценный рабочий фоновый сервис:
1. **Реальное локальное хранилище (`FileSystemLocalVault`)**: реализация порта `LocalVault` поверх файловой системы с рекурсивным обходом, защитой от порчи заметок через атомарную запись во временные файлы, фильтрацией системных и служебных файлов Obsidian (`SyncRules`), а также реактивным отслеживанием локальных изменений (`VaultWatcher` с debounce).
2. **Фоновый оркестратор (`DaemonRuntime` & `SyncScheduler`)**: координация периодической синхронизации, опрос удаленного Google Drive Changes Feed, реакция на локальные события файловой системы, предотвращение наложений параллельных синхронизаций (`Mutex`) и исключение циклов самовозбуждения (echo loop).
3. **Хранилище конфигурации и учетных данных**: сохранение и загрузка настроек демона (`config.json`), мастер-пароля шифрования (env / secret file), OAuth-токенов (`tokens.json`) и индекса синхронизации (`index.json`) в профиле пользователя (`~/.config/mimir/`).
4. **Локальный HTTP API (Ktor Server CIO)**: интерфейс для web-ui и плагина Obsidian (`/health`, `/api/v1/status`, `/api/v1/sync`, `/api/v1/conflicts`, `/api/v1/conflicts/resolve`, `/api/v1/auth/device/*`, `/api/v1/unlock`), раздача статики Wasm Web UI, обработка занятого порта с кодом возврата 2.

---

## 2. Цели, не-цели и анти-цели

### 2.1 Цели
- **Полнофункциональный `FileSystemLocalVault`**:
  - Реализация в `core` поверх `kotlinx.io.files` (`Path`, `SystemFileSystem`) для переиспользования в `daemon`, `app-desktop` и `app-android`.
  - Атомарная запись файлов через временные файлы (`.mimir_tmp_<uuid>`) в том же каталоге с последующим переименованием/заменой.
  - Рекурсивный обход и сбор метаданных (`LocalEntry`: `VaultFileId`, `relativePath`, `size`, `modifiedAt`).
  - Фильтрация путей через `SyncRules` (`.git/**`, `.obsidian/workspace*.json`, `.obsidian/cache/**`, `.trash/**`, `**/*.tmp`, `**/.mimir*`).
  - Абстракция `VaultWatcher`: реализация `NioWatchServiceVaultWatcher` (в `core/jvmMain`) с таймером подавления дребезга (debounce 1000 мс) и универсальный `PollingVaultWatcher` (в `commonMain`) как fallback.
- **Фоновый планировщик `SyncScheduler`**:
  - Автоматический запуск синхронизации по таймеру (настраиваемый интервал, по умолчанию 30 с).
  - Реактивный запуск при обнаружении локальных правок через `VaultWatcher` (с задержкой debounce).
  - Опрос изменений Google Drive через `GoogleDriveStorage.listChanges(startPageToken)` для минимизации сетевого трафика и квот.
  - Строгая сериализация через `Mutex`: единовременно выполняется не более одного прогона синхронизации; параллельные запросы становятся в очередь или отклоняются с кодом 409.
  - Подавление эхо-эффекта (echo suppression): запись файлов, загруженных из облака, не вызывает повторную отправку их обратно в облако.
- **Персистентное состояние в файловой системе**:
  - Конфигурация `DaemonConfig`: чтение из JSON-файла (`~/.config/mimir/config.json`), переопределение через переменные окружения (`MIMIR_PORT`, `MIMIR_BIND`, `MIMIR_VAULT`, `MIMIR_TOKEN`, `MIMIR_PASSWORD`, `MIMIR_PASSWORD_FILE`, `MIMIR_GOOGLE_CLIENT_ID`, `MIMIR_GOOGLE_CLIENT_SECRET`, `MIMIR_SYNC_INTERVAL`, `MIMIR_ROOT_FOLDER`, `MIMIR_CONFIG_DIR`).
  - Сохранение токенов `FileTokenStorage` (`~/.config/mimir/tokens.json`) с ограничением прав доступа (POSIX 0600 на Unix).
  - Сохранение индекса `JsonSyncStateStore` (`~/.config/mimir/index.json`) с атомарной записью.
- **Ktor HTTP API v1 в модуле `daemon`**:
  - Запуск на `127.0.0.1:47821` (или `0.0.0.0` в Docker). При занятом порте — немедленный выход с кодом 2 и выводом `Port <port> on <bind> is already in use` без стектрейса.
  - Опциональная авторизация через заголовок `Authorization: Bearer <token>` или `X-Mimir-Token: <token>`.
  - Эндпоинты `/health`, `/api/v1/status`, `/api/v1/sync`, `/api/v1/conflicts`, `/api/v1/conflicts/resolve`, `/api/v1/auth/device/start`, `/api/v1/auth/device/poll`, `/api/v1/unlock`.
  - Раздача статического Wasm-бандла `:web-ui` по корневому пути `/`.

### 2.2 Не-цели
- Создание графического интерфейса (Desktop Compose UI, tray icon, StatusNotifierItem для Wayland/niri) — это Этап 5 (`app-desktop`).
- Реализация фоновой службы Android (`WorkManager`, Foreground Service) — это Этап 6 (`app-android`).
- Реализация клиентского JS-плагина для Obsidian — это Этап 7 (`obsidian-plugin`).
- Поддержка многопользовательского удаленного доступа через публичный интернет (демон строго локальный, HTTPS/TLS и внешняя аутентификация вне скоупа).

### 2.3 Анти-цели
- Потеря или частичная перезапись заметок при сбое питания или аварийном завершении процесса (прямая запись в рабочий файл запрещена).
- Циклический шторм синхронизации (загрузка файла из Drive -> триггер watcher -> повторный upload в Drive).
- Блокировка демона и зависание HTTP API при сетевых таймаутах Google Drive или отсутствии токенов.
- Утечка OAuth refresh_token или мастер-пароля шифрования в stdout/stderr/логи.

---

## 3. Ограничения

1. **Стек и окружение**: Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8, Ktor Server CIO 3.6.0.
2. **Иерархия зависимостей**: `daemon` зависит от `core` и `drive`. Модуль `core` не зависит от `drive`, платформ и внешних сетевых библиотек.
3. **Идентификаторы**: порт по умолчанию `47821` задается в `gradle.properties` (`mimir.apiDefaultPort`) и транслируется через `BuildConfig.API_DEFAULT_PORT`.
4. **Хранилище**: SQLite категорически запрещен. Состояние хранится в текстовых/JSON-файлах с атомарной перезаписью.

---

## 4. Архитектурные решения по результатам Builder-Check

| Вопрос | Принятое решение | Обоснование |
| :--- | :--- | :--- |
| **Мастер-пароль шифрования** | Передается через `MIMIR_PASSWORD` (env), `MIMIR_PASSWORD_FILE` (файл 0600) или через эндпоинт `POST /api/v1/unlock` | Полное соответствие спеке §6.3 (Docker secret / env с правами 600) + поддержка разблокировки из Web UI |
| **Google OAuth Client ID & Secret** | Читаются из `DaemonConfig` / env (`MIMIR_GOOGLE_CLIENT_ID`, `MIMIR_GOOGLE_CLIENT_SECRET`) с fallback на `mise.local.toml` | Гибкость для self-hosted / Docker запусков и безопасность секретов |
| **Жизненный цикл при неполной конфигурации** | HTTP API стартует всегда. При `vaultPath == null` или отсутствии токенов статус — `not connected`. `/api/v1/sync` возвращает `412 Precondition Failed` | Позволяет поднять сервис в Docker и провести начальную настройку/авторизацию через Web UI |
| **Модель конфликтов** | Файловая: сканирование по паттерну `* (conflicted copy *).*`. Разрешение через удаление или замену файла | Исключает дублирование состояния в `index.json`, строгое следование Zero Data Loss |
| **Авторизация API** | При `token == null` на `127.0.0.1` авторизация не требуется (для локального плагина). При наличии токена — строгая проверка 401 | Удобство при локальной разработке, безопасность при экспорте порта |

---

## 5. Детальный дизайн компонентов

```
+---------------------------------------------------------------------------------+
|                                 DAEMON MODULE                                   |
|                                                                                 |
|  +---------------------+   +---------------------+   +-----------------------+  |
|  |     Main.kt         |   |    DaemonConfig     |   |   FileTokenStorage    |  |
|  | (Port Check, CIO)   |   | (mimir.json / env)  |   | (0600 tokens.json)    |  |
|  +----------+----------+   +----------+----------+   +-----------+-----------+  |
|             |                         |                          |              |
|             v                         v                          v              |
|  +---------------------+   +-------------------------------------------------+  |
|  |    mimirRoutes      |   |                 DaemonRuntime                   |  |
|  |  (Ktor CIO Engine)  |   |  - Orchestrates Engine, Vault, Drive, Scheduler |  |
|  +----------+----------+   +------------------------+------------------------+  |
|             |                                       |                           |
+-------------|---------------------------------------|---------------------------+
              |                                       |
              | calls                                 | runs
              v                                       v
+------------------------------------+   +----------------------------------------+
|             Ktor API               |   |             SyncScheduler              |
|  GET  /health                      |   |  - Periodic Timer (30s)                |
|  GET  /api/v1/status               |   |  - VaultWatcher Debounce Trigger       |
|  POST /api/v1/sync                 |   |  - Drive ChangeFeed Polling            |
|  GET  /api/v1/conflicts            |   |  - Mutex Concurrency Lock              |
|  POST /api/v1/conflicts/resolve    |   |  - Echo Suppression Filter             |
|  POST /api/v1/auth/device/start    |   +-------------------+--------------------+
|  GET  /api/v1/auth/device/poll     |                       |
|  POST /api/v1/unlock               |                       |
+------------------------------------+                       |
                                                             v
+---------------------------------------------------------------------------------+
|                                 CORE MODULE                                     |
|                                                                                 |
|  +---------------------------------+   +-------------------------------------+  |
|  |       FileSystemLocalVault      |   |          DefaultSyncEngine          |  |
|  |  - kotlinx.io.files             |   |  - 3-Way Reconciliation             |  |
|  |  - Atomic writes (.tmp -> mv)   |   |  - SyncPlan Execution               |  |
|  |  - SyncRules Exclusion          |   |  - JsonSyncStateStore (index.json)  |  |
|  +----------------+----------------+   +------------------+------------------+  |
|                   |                                       |                     |
|                   v                                       v                     |
|  +---------------------------------+   +-------------------------------------+  |
|  |           VaultWatcher          |   |       EncryptedRemoteStorage        |  |
|  |  - Jvm: NioWatchService         |   |         (RcloneCipher +             |  |
|  |  - Common: PollingFallback      |   |       GoogleDriveStorage)           |  |
|  +---------------------------------+   +-------------------------------------+  |
+---------------------------------------------------------------------------------+
```

### 5.1 Локальное хранилище: `FileSystemLocalVault` & `VaultWatcher`

#### Структура классов в `core`

```kotlin
package dev.mimir.core.fs

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlin.time.Instant

interface VaultWatcher {
    fun start(): Flow<VaultChange>
    fun stop()
    fun markWrittenBySync(relativePath: String)
}

class FileSystemLocalVault(
    val rootPath: Path,
    private val rules: SyncRules = SyncRules(),
    private val watcher: VaultWatcher? = null,
) : LocalVault {
    override suspend fun list(): List<LocalEntry>
    override suspend fun getEntry(relativePath: String): LocalEntry?
    override suspend fun read(id: VaultFileId): Source
    override suspend fun write(id: VaultFileId, data: Source)
    override suspend fun write(relativePath: String, data: Source): LocalEntry
    override suspend fun delete(id: VaultFileId)
    override suspend fun deleteByPath(relativePath: String)
    override fun changes(): Flow<VaultChange>
}
```

#### Инварианты файловых операций:
1. **Идентификация файлов (`VaultFileId`)**: детерминированный стабильный идентификатор на базе относительного нормализованного пути (`VaultFileId("fs:${normalizedPath}")`).
2. **Атомарная запись**:
   - Данные пишутся во временный файл в том же подкаталоге: `<dir>/.mimir_tmp_<uuid>`.
   - Временный файл сбрасывается на диск (`flush()`) и закрывается.
   - Выполняется атомарное переименование/замена в целевой файл `targetPath` (`SystemFileSystem.atomicMove` или платформенный `rename`).
   - Если запись выполняется движком синхронизации, путь регистрируется в `watcher.markWrittenBySync(path)` для предотвращения эхо-события.
3. **Фильтрация путей (`SyncRules`)**:
   - При рекурсивном обходе директорий исключаются каталоги и файлы, удовлетворяющие `rules.isExcluded(relativePath)`:
     - `.git/**`, `.obsidian/workspace*.json`, `.obsidian/cache/**`, `.trash/**`, `**/*.tmp`, `**/*.crswap`, `**/.mimir*`.
   - Если директория исключена (например, `.git`), рекурсивный спуск в нее не производится.

#### Отслеживание изменений (`VaultWatcher`):
- `NioWatchServiceVaultWatcher` (в `core/jvmMain`):
  - Рекурсивная регистрация каталогов через `java.nio.file.WatchService`.
  - Отслеживание `ENTRY_CREATE`, `ENTRY_MODIFY`, `ENTRY_DELETE`.
  - Динамическая регистрация вновь созданных подкаталогов.
  - Подавление дребезга (debounce): события группируются по путям с задержкой 1000 мс перед эмитом в `Flow<VaultChange>`.
  - Фильтр эхо-записей: если файл помечен `markWrittenBySync(path)`, входящее событие модификации от собственной записи игнорируется в течение окна TTL (2000 мс).
- `PollingVaultWatcher` (в `core/commonMain`):
  - Периодическое сканирование снимка состояния (`Map<relativePath, Pair<size, mtime>>`).
  - Используется как fallback или в окружениях без поддержки `WatchService`.

---

### 5.2 Конфигурация и персистентное состояние

#### Расположение файлов конфигурации на хосте:
- Базовый каталог: `~/.config/mimir/` (или `$XDG_CONFIG_HOME/mimir`, на Windows `%APPDATA%\Mimir`).
- `config.json`: общие настройки демона.
- `tokens.json`: сохраненные токены OAuth Google Drive (`chmod 0600` на POSIX).
- `index.json`: сериализованный `SyncIndex` (сопоставление baseline-хешей и ревизий).

#### Модель конфигурации `DaemonConfig`:

```kotlin
@Serializable
data class DaemonConfig(
    val bind: String = "127.0.0.1",
    val port: Int = BuildConfig.API_DEFAULT_PORT,
    val vaultPath: String? = null,
    val token: String? = null,
    val password: String? = null,
    val passwordFile: String? = null,
    val googleClientId: String? = null,
    val googleClientSecret: String? = null,
    val syncIntervalSeconds: Long = 30L,
    val rootFolder: String = "MimirVault",
    val configDir: String? = null,
) {
    companion object {
        fun load(configDir: String? = null, env: Map<String, String> = System.getenv()): DaemonConfig
    }
}
```

#### Приоритет применения параметров:
1. Переменные окружения (`MIMIR_PORT`, `MIMIR_BIND`, `MIMIR_VAULT`, `MIMIR_TOKEN`, `MIMIR_PASSWORD`, `MIMIR_PASSWORD_FILE`, `MIMIR_GOOGLE_CLIENT_ID`, `MIMIR_GOOGLE_CLIENT_SECRET`, `MIMIR_SYNC_INTERVAL`, `MIMIR_ROOT_FOLDER`, `MIMIR_CONFIG_DIR`).
2. Файл конфигурации `config.json` в `configDir`.
3. Значения по умолчанию (`127.0.0.1`, порт `47821`, интервал `30`, папка `MimirVault`).

#### Хранилище токенов `FileTokenStorage`:
- Реализует `TokenStorage` из модуля `drive`.
- Загружает `OAuthTokens` из `tokens.json`. При сохранении производит атомарную запись во временный файл и выставляет права `rw-------` (POSIX 600).

---

### 5.3 Оркестрация рантайма и планировщик синхронизации

#### Класс `DaemonRuntime`:
- Управляет жизненным циклом:
  - Инициализирует `FileTokenStorage` и `GoogleAuthClient`.
  - При наличии мастер-пароля разблокирует `RcloneCipher` и собирает `EncryptedRemoteStorage(GoogleDriveStorage, cipher)`.
  - При наличии `vaultPath` собирает `FileSystemLocalVault` с `NioWatchServiceVaultWatcher`.
  - Инициализирует `DefaultSyncEngine` с `JsonSyncStateStore`.
  - Запускает `SyncScheduler`.
- Предоставляет методы для динамической разблокировки паролем (`unlock(password)`) и изменения пути к vault.

#### Класс `SyncScheduler`:

```kotlin
class SyncScheduler(
    private val syncEngine: SyncEngine,
    private val localVault: LocalVault,
    private val remoteStorage: RemoteStorage,
    private val syncInterval: Duration = 30.seconds,
    private val scope: CoroutineScope,
) {
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant? = null
    private var driveStartPageToken: String? = null

    fun start()
    fun stop()
    suspend fun triggerSync(): Boolean
    fun lastSync(): Instant?
}
```

---

### 5.4 Спецификация локального HTTP API (Ktor Server CIO)

#### Базовые соглашения:
- Все ответы API отдаются в формате `application/json; charset=utf-8`.
- При наличии настроенного `DaemonConfig.token`:
  - Запросы к `/api/v1/*` (кроме `/health` и статики `/`) требуют валидный токен:
    `Authorization: Bearer <token>` или заголовок `X-Mimir-Token: <token>`.
  - При отсутствии или несовпадении токена возвращается статус `401 Unauthorized` с телом `{"error": "Unauthorized"}`.

#### Эндпоинты:

##### 1. `GET /health`
- **Назначение**: Проверка работоспособности сервиса.
- **Авторизация**: Не требуется.
- **Ответ `200 OK`**:
```json
{
  "status": "ok",
  "version": "0.1.0"
}
```

##### 2. `GET /api/v1/status`
- **Назначение**: Получение текущего состояния синхронизации, аккаунта и конфликтов.
- **Ответ `200 OK`**:
```json
{
  "status": "idle",
  "label": "Mimir: idle",
  "vaultPath": "/home/user/vault",
  "lastSyncAt": "2026-10-24T12:00:00Z",
  "activeConflicts": 0,
  "accountEmail": "user@gmail.com",
  "isLocked": false
}
```
*(При отсутствии подключения статус — `Mimir: not connected`)*.

##### 3. `POST /api/v1/sync`
- **Назначение**: Запуск немедленной синхронизации.
- **Ответ `202 Accepted`** (синхронизация запущена):
```json
{
  "status": "started"
}
```
- **Ответ `409 Conflict`** (синхронизация уже в процессе выполнения):
```json
{
  "status": "already_running"
}
```
- **Ответ `412 Precondition Failed`** (хранилище не настроено, не разблокировано или отсутствует OAuth):
```json
{
  "error": "Vault path, encryption password or cloud storage not configured"
}
```

##### 4. `POST /api/v1/unlock`
- **Назначение**: Разблокировка шифрования мастер-паролем, если он не был задан при старте.
- **Тело запроса**:
```json
{
  "password": "secret_vault_password"
}
```
- **Ответ `200 OK`**:
```json
{
  "status": "unlocked"
}
```

##### 5. `GET /api/v1/conflicts`
- **Назначение**: Список неразрешенных конфликтов (конфликтных копий файлов, обнаруженных в локальном vault).
- **Ответ `200 OK`**:
```json
{
  "conflicts": [
    {
      "originalPath": "Notes/Architecture.md",
      "conflictPath": "Notes/Architecture (conflicted copy 2026-10-24-123000).md",
      "createdAt": "2026-10-24T12:30:00Z"
    }
  ]
}
```

##### 6. `POST /api/v1/conflicts/resolve`
- **Назначение**: Разрешение конфликта в пользу оригинальной или конфликтной версии.
- **Тело запроса**:
```json
{
  "originalPath": "Notes/Architecture.md",
  "conflictPath": "Notes/Architecture (conflicted copy 2026-10-24-123000).md",
  "resolution": "keep_conflict" 
}
```
*(где `resolution`: `"keep_original"` — удалить конфликтный файл; `"keep_conflict"` — перезаписать оригинал конфликтной копией и удалить конфликтный файл)*.
- **Ответ `200 OK`**:
```json
{
  "status": "resolved",
  "path": "Notes/Architecture.md"
}
```

##### 7. `POST /api/v1/auth/device/start`
- **Назначение**: Запуск процедуры входа через Device Code Grant (RFC 8628).
- **Ответ `200 OK`**:
```json
{
  "verification_url": "https://www.google.com/device",
  "user_code": "ABCD-EFGH",
  "device_code": "dev_code_sample_123",
  "expires_in": 1800,
  "interval": 5
}
```

##### 8. `GET /api/v1/auth/device/poll?device_code=...`
- **Назначение**: Опрос статуса подтверждения кода пользователем.
- **Ответ `200 OK` (ожидание ввода пользователем)**:
```json
{
  "status": "pending"
}
```
- **Ответ `200 OK` (успешная авторизация, токены сохранены в `tokens.json`)**:
```json
{
  "status": "authorized",
  "email": "user@gmail.com"
}
```
- **Ответ `400 Bad Request` (код просрочен или отклонен)**:
```json
{
  "status": "expired",
  "error": "Code has expired"
}
```

##### 9. Раздача статики Web UI:
- `GET /` и любые статические ресурсы раздаются из Wasm-бандла `:web-ui` через Ktor `staticResources("/", "static")`.

---

## 6. Стратегия тестирования и верификации

### 6.1 Модульные тесты `core` (`core/commonTest` и `core/jvmTest`)
1. **`FileSystemLocalVaultTest`** (JVM):
   - Рекурсивный обход тестового каталога во временной директории (`createTempDirectory`).
   - Фильтрация служебных файлов `.git/HEAD`, `.obsidian/workspace.json`, `.obsidian/cache/data`, временных файлов `.mimir_tmp_*`.
   - Атомарная запись: проверка появления временного файла в процессе и итогового файла после завершения.
   - Метаданные: проверка корректности вычисления `size` и `modifiedAt`.
2. **`VaultWatcherTest`** (JVM):
   - Симуляция создания, изменения и удаления файлов на диске.
   - Проверка срабатывания `debounce` (несколько быстрых записей объединяются в одно событие).
   - Проверка подавления эхо (`markWrittenBySync`).

### 6.2 Модульные тесты `daemon` (`daemon/src/test`)
1. **`DaemonConfigTest`**:
   - Загрузка из JSON-файла.
   - Корректность переопределения через env-переменные (`MIMIR_PORT`, `MIMIR_BIND`, `MIMIR_TOKEN`, `MIMIR_PASSWORD`).
   - Валидация диапазонов портов и путей.
2. **`FileTokenStorageTest`**:
   - Сохранение и загрузка токенов.
   - Проверка прав доступа файла (0600 на POSIX).
3. **`SyncSchedulerTest`**:
   - Проверка блокировки через `Mutex` при параллельных триггерах.
   - Симуляция тиков таймера и Change Feed polling.
4. **`RoutesTest` (Ktor Server Test Host)**:
   - Проверка `/health` и статуса версии.
   - Проверка авторизации: отказ `401 Unauthorized` при отсутствии токена, успех при корректном токене.
   - Проверка роутов `/api/v1/status`, `/api/v1/sync`, `/api/v1/conflicts`, `/api/v1/conflicts/resolve`, `/api/v1/unlock`.
   - Проверка роутов Device Code flow (`/api/v1/auth/device/start`, `/api/v1/auth/device/poll`).
5. **`PortInUseTest`**:
   - Проверка выхода с кодом 2 и диагностическим сообщением без стектрейса при занятом порте.

### 6.3 Сквозной интеграционный тест полного цикла (E2E)
- Инициализация `FileSystemLocalVault` на реальной временной папке.
- Подключение `EncryptedRemoteStorage(MockGoogleDriveStorage, cipher)`.
- Сценарий 1: создание Markdown-заметки в локальном vault -> срабатывание watcher -> планировщик запускает sync -> шифрование имени и содержимого -> появление зашифрованных файлов в Mock Google Drive.
- Сценарий 2: удаленное добавление файла в Mock Google Drive -> обновление Change Feed -> срабатывание планировщика -> расшифровка -> появление заметки в локальном `FileSystemLocalVault`.
- Сценарий 3: одновременная правка -> появление файла `(conflicted copy ...)` в vault и в удаленном хранилище.

---

## 7. На усмотрение исполнителя

1. Конкретная реализация debounce-таймера в `VaultWatcher` (через `kotlinx.coroutines.flow.debounce` или корутинный канал с таймером).
2. Форматирование логов в `daemon` (конфигурация SLF4J / `simplelogger.properties`).
3. Дополнительные вспомогательные CLI-параметры демона (например, `--config <path>`).
