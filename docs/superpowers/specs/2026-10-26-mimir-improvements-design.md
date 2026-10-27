# Mimir — Спецификация улучшений ядра, демона и UI перед разработкой тонких клиентов и Android-версии

Дата: 2026-10-26  
Статус: проект на согласовании (Ready for Review)  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md`  
Связанные спеки:
- `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md` (HTTP API и DaemonRuntime)
- `docs/superpowers/specs/2026-10-24-mimir-sync-io-design.md` (Бюджет IO и потоковая передача)
- `docs/superpowers/specs/2026-10-24-mimir-desktop-design.md` (Десктопный клиент)
- `docs/superpowers/specs/2026-10-24-mimir-obsidian-ui-and-onboarding-design.md` (Мастер настройки)

---

## 1. Замысел и цели

По результатам комплексного аудита архитектуры, асинхронности, безопасности типов и кроссплатформенной готовности выявлен ряд критических архитектурных долгов и блокеров, препятствующих эффективной разработке тонких клиентов (плагин Obsidian, расширение браузера Chromium) и портированию на Android 16 (API 36).

Настоящая спецификация формулирует требования и архитектурные решения по устранению выявленных проблем без усложнения кодовой базы и без привлечения сторонних языков программирования (Rust/C):
1. **Выделение общего KMP-модуля `:client`**: консолидация контракта `DaemonService`, сериализуемых DTO-моделей и `HttpDaemonClient` для совместного использования в десктопном приложении (`app-desktop`), плагине Obsidian (`obsidian-plugin` на Kotlin/JS) и Web UI (`web-ui` на WasmJs).
2. **Устранение OOM-уязвимости в `GoogleDriveStorage`**: замена буферизации файла целиком в массив байтов (`response.body<ByteArray>()`) на потоковый `Source` из Ktor `ByteReadChannel`.
3. **Поддержка CORS в HTTP API демона**: конфигурирование плагина Ktor `CORS` для обслуживания запросов от браузерного расширения (`chrome-extension://*`) и плагина Obsidian (`app://obsidian.md`).
4. **Асинхронная инициализация шифрования (`RcloneCipher`)**: вынос ресурсоемкой операции деривации ключа `Scrypt.derive` из синхронного конструктора в `suspend`-фабрику с выполнением на пуле `Dispatchers.Default`.
5. **Оптимизация фонового мониторинга конфликтов**: устранение 5-секундного холостого опроса файловой системы (`refreshConflicts`) в `DaemonRuntime` с переходом на реактивную инвалидацию по событиям `VaultWatcher`.
6. **Подготовка Compose UI под стандарты Android 16**: обеспечение корректного Insetting (`Modifier.safeDrawingPadding()`), адаптация сенсорных мишеней (Touch Targets ≥ 48 dp на сенсорных экранах) и модульная декомпозиция `SetupWizardScreen.kt`.
7. **Формирование фундамента Android 16**: реализация `AndroidFileObserverVaultWatcher` (взамен неподдерживаемого на Android Java NIO `WatchService`) и переход от непрерывного `FOREGROUND_SERVICE_DATA_SYNC` к `WorkManager` с учетом 6-часовых лимитов Android 15/16.

---

## 2. Цели, не-цели и анти-цели

### 2.1 Цели
- Создать модуль `:client` с таргетами `jvm`, `js`, `wasmJs`, `androidTarget`, перенеся туда контракт `DaemonService`, DTO-модели из `daemon/Routes.kt` и переносимый `HttpDaemonClient`.
- Внедрить потоковое чтение в `GoogleDriveStorage.read(path)`: потребление памяти при скачивании файла любого размера (включая видеовложения 200+ МБ) не должно превышать размера чанка буфера (64 КБ).
- Настроить CORS в `mimirRoutes` с поддержкой методов GET, POST, OPTIONS, заголовков `Authorization`, `X-Mimir-Token`, `Content-Type`.
- Добавить фабричный метод `RcloneCipher.create(password, ...): RcloneCipher` с выполнением Scrypt на `Dispatchers.Default` и возможностью отмены.
- Заменить периодический `conflictScanInterval` (5 с) в `DaemonRuntime` на реактивное обновление снимка конфликтов: при локальных изменениях из `VaultWatcher` и при завершении прогона синхронизации.
- Переписать `DaemonRuntime.resolveConflict` на потоковую передачу данных между источником и приемником без вызова `readByteArray()`.
- Добавить `safeDrawingPadding()` в корневой слой Compose UI и реализовать декомпозицию `SetupWizardScreen.kt` на отдельные файлы шагов (< 300 строк каждый).
- Спроектировать архитектуру фоновой службы Android на базе `WorkManager` (PeriodicWorkRequest + Expedited OneTimeWorkRequest) и `FileObserver`.

### 2.2 Не-цели
- Непосредственная реализация UI и логики самого плагина Obsidian (отдельный этап реализации `:obsidian-plugin`).
- Реализация всплывающего окна и логики шифрования расширения браузера (отдельный этап `:browser-extension`).
- Публикация APK в каталоге Google Play Store (распространение ведется через GitHub Releases вне каталога, что сохраняет легитимность `MANAGE_EXTERNAL_STORAGE`).

### 2.3 Анти-цели
- Внедрение нативных динамических библиотек на Rust/C (через JNI/cinterop): сохраняется чистый Kotlin/JVM и Kotlin Multiplatform для 100% совместимости с выравниванием страниц 16 КБ в Android 15/16.
- Блокировка UI-потока при деривации паролей и шифровании файлов.
- Молчаливое подавление потери связи с демоном в клиентах: пользовательский интерфейс обязан явно визуализировать статус обрыва соединения.

---

## 3. Инварианты Mimir

### 3.1 Платформы
| Платформа | Реализация / Поведение |
| :--- | :--- |
| **Linux desktop (Arch/niri/GNOME)** | Системная Java ≥ 27; Ktor CIO демон; NIO `WatchService`; D-Bus StatusNotifierItem трей; десктопные размеры кнопок (32 dp) |
| **Windows desktop** | Вшитая JRE; Ktor CIO демон; NIO `WatchService`; AWT Tray; учет нечувствительности ФС к регистру; пути через `Path` |
| **Android (Android 16 / API 36)** | Без NIO: `AndroidFileObserverVaultWatcher` поверх `android.os.FileObserver`; синхронизация через `WorkManager`; `Modifier.safeDrawingPadding()`; сенсорные мишени ≥ 48 dp; разрешения `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS` |
| **Docker-демон** | Linux glibc, без дисплея; бинд `0.0.0.0`; раздача Wasm Web UI; авторизация Device Code |
| **JS-клиенты (Obsidian & Chromium Extension)** | Модуль `:client` на Kotlin/JS; CORS-доступ к демону; отсутствие блокировок event loop при вводе мастер-пароля |

### 3.2 Дизайн
- **Фасад + Удаленный заместитель**: Контракт `DaemonService` и клиент `HttpDaemonClient` изолированы в модуле `:client`. `app-desktop`, `obsidian-plugin` и `web-ui` используют одинаковый интерфейс, скрывающий тип подключения (локальный процесс или сетевой HTTP).
- **Потоки данных (Streaming)**: Взаимодействие между сетевым адаптером `GoogleDriveStorage`, шифром `RcloneCipher` и файловой системой `FileSystemLocalVault` осуществляется строго через потоки `kotlinx.io.Source` и `kotlinx.io.Sink`. Аллокация промежуточных массивов байтов `ByteArray` запрещена.
- **Декомпозиция UI**: Правило ограничения размера файлов (< 300 строк). `SetupWizardScreen.kt` разбивается на composable-файлы шагов (`VaultStep.kt`, `GoogleStep.kt`, `PasswordStep.kt`, `InitialResolutionStep.kt`).
- **UDF и UI-модели**: Интерфейс `MimirUiModel` сохраняется в модуле `:ui` как контракт для Composable-экранов и фейков в тестах (`FakeMimirUiModel`). `DaemonServiceUiModel` переносится в `:ui` и реализует `MimirUiModel` поверх `DaemonService`. Класс `EngineMimirUiModel` сохраняется для прямого встраивания движка без сетевого демона (тесты, превью, fallback).
- **Состояние связи с демоном**: `DaemonService` объявляет `val connectionStatus: StateFlow<DaemonConnectionStatus>` (`Connected`, `Reconnecting`, `Disconnected`). `SyncStatus` в `:core` не изменяется, что исключает регрессию инвариантов и тестов ядра.
- **Декаплинг от адаптеров облака**: Метод `DaemonService.startDeviceLogin()` возвращает DTO из модуля `:client` (`DaemonDeviceCodeDto`), что исключает зависимость `:client` от `:drive` и сохраняет минимальный размер бандла JS-клиентов.

### 3.3 IO (Бюджет и стоимость)
- **Скачивание файла**: 1 HTTP-запрос к Google Drive (`alt=media`), данные читаются блоками по 64 КБ через Ktor `ByteReadChannel`, дешифруются на лету потоком `DecryptingSource` и записываются в локальный временный файл. Потребление памяти: $O(1)$ относительно размера файла (максимум ~64 КБ).
- **Разрешение конфликта**: 0 полных чтений в память; чтение из файла конфликта и запись в целевой файл идут через `transferTo(sink)`.
- **Фоновый мониторинг**: 0 обращений к диску при отсутствии изменений в vault (устранение холостого вызова `vault.list()` каждые 5 с).

### 3.4 UX
- **Состояния связи с демоном**:
  - `Connected` (штатный обмен).
  - `Disconnected / Reconnecting` (демон перезапускается или упал — показ плашки переподключения вместо залипания в статусе `Idle`/`Syncing`).
- **Сенсорный ввод на мобильных устройствах**: Все интерактивные элементы (кнопки, чекбоксы, пункты списков) на сенсорных экранах имеют габариты не менее 48×48 dp с отступами не менее 8 dp.
- **Интеграция с Android 15/16 Edge-to-Edge**: Контент гарантированно не перекрывается системными вырезами (camera cutout), системной панелью уведомлений и полосой жестов навигации.

---

## 4. Детальный дизайн архитектурных изменений

### 4.1 Новый KMP-модуль `:client`

Создается независимый модуль в дереве проектов:
```
mimir/
├── core/
├── drive/
├── client/           # НОВЫЙ МОДУЛЬ (KMP: jvm, js, wasmJs, android)
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/dev/mimir/client/
│       ├── DaemonService.kt        # Контракт сервиса
│       ├── DaemonDto.kt            # Сериализуемые DTO модели
│       └── HttpDaemonClient.kt     # Универсальный HTTP клиент Ktor
├── daemon/
├── ui/
├── app-desktop/
├── obsidian-plugin/
└── browser-extension/
```

#### Зависимости модуля `:client`
```kotlin
// client/build.gradle.kts
plugins {
    id("mimir.kmp-library")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
        }
    }
}
```

#### Единые DTO-модели (`DaemonDto.kt`)
Все модели данных взаимодействия с HTTP API выносятся из закрытых `private` определений в общедоступные типизированные структуры:
```kotlin
package dev.mimir.client

import kotlinx.serialization.Serializable

@Serializable
data class DaemonStatusDto(
    val status: String,
    val label: String,
    val vaultPath: String?,
    val lastSyncAt: String?,
    val isLocked: Boolean,
    val activeConflicts: Int,
    val accountEmail: String?,
    val progress: DaemonSyncProgressDto? = null,
)

@Serializable
data class DaemonConflictItemDto(
    val originalPath: String,
    val conflictPath: String,
    val createdAt: String,
)

@Serializable
data class DaemonResolveConflictRequest(
    val originalPath: String,
    val conflictPath: String,
    val resolution: String, // "keep_original" | "keep_conflict"
)

@Serializable
data class DaemonUnlockRequest(val password: String)

@Serializable
data class DaemonVaultRequest(val vaultPath: String)

@Serializable
data class DaemonDeviceCodeDto(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

enum class DaemonConnectionStatus {
    Connected,
    Reconnecting,
    Disconnected,
}
```

---

### 4.2 Потоковое чтение `GoogleDriveStorage.read`

В модуле `drive` метод чтения переписывается с `response.body<ByteArray>()` на адаптацию `ByteReadChannel` в `Source`:

```kotlin
// drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt
override suspend fun read(path: RemotePath): Source {
    val (client, pathResolver) = ensureConfigured()
    val file = pathResolver.resolvePath(path)
        ?: throw NoSuchElementException("No such file in Drive: ${path.value}")

    val response = client.executeWithRetry { token ->
        client.httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
        }
    }

    if (!response.status.isSuccess()) {
        throw DriveHttpException(response.status, "Failed to read file ${file.id}")
    }

    // Возвращаем потоковый Source над неблокирующим каналом Ktor
    return response.bodyAsChannel().asSource().buffered()
}
```

**Уточнение (реализация):** потоковость обеспечена на JVM и Android (`DriveHttpClient.executeStreaming` + `ByteChannel` с обратным давлением; `asSource()` для JS/wasm не существует). Скачивание из Drive выполняют только демон и Android; JS-клиенты ходят в демон через `:client`, поэтому на JS/wasm тело читается целиком (`readBuffer()`). Разрешение источников CORS ограничено `chrome-extension://` и `app://obsidian.md`: `anyHost()` с `allowCredentials` открывал API любому сайту.

Адаптер `asSource()` для Ktor `ByteReadChannel` гарантирует чтение кусками без аллокации буфера под весь файл в памяти.

---

### 4.3 Поддержка CORS в HTTP API демона

В [`Routes.kt`](file:///home/gidragir/projects/mimir/daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt) подключается плагин `CORS`:

```kotlin
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders

fun Application.mimirRoutes(service: DaemonService, config: DaemonConfig) {
    install(ContentNegotiation) { json() }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Mimir-Token")
        
        // Разрешение источников: локальный хост, расширение браузера, плагин Obsidian
        anyHost() // На loopback интерфейсе (127.0.0.1) безопасно с учетом проверки Bearer-токена
        allowCredentials = true
    }
    // ... дальнейшая маршрутизация
}
```

---

### 4.4 Асинхронная фабрика `RcloneCipher`

Для исключения блокировки UI-потока и зависаний в JS/Wasm:
1. Конструктор `RcloneCipher` становится `internal`.
2. Публичный доступ предоставляется через фабрику:
```kotlin
class RcloneCipher internal constructor(
    private val dataKey: ByteArray,
    private val nameEme: Eme,
    private val nameTweak: ByteArray,
    private val nonceSource: () -> ByteArray = ::randomNonce,
) : Cipher {
    companion object {
        suspend fun create(
            password: String,
            password2: String? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            nonceSource: () -> ByteArray = ::randomNonce,
        ): RcloneCipher = withContext(dispatcher) {
            require(password.isNotEmpty()) { "password must not be empty" }
            val salt = if (password2.isNullOrEmpty()) DEFAULT_SALT else password2.encodeToByteArray()
            val keys = Scrypt.derive(password.encodeToByteArray(), salt, 16384, 8, 1, 80)
            RcloneCipher(
                dataKey = keys.copyOfRange(0, 32),
                nameEme = Eme(keys.copyOfRange(32, 64)),
                nameTweak = keys.copyOfRange(64, 80),
                nonceSource = nonceSource,
            )
        }
    }
}
```

---

### 4.5 Реактивный мониторинг конфликтов в `DaemonRuntime`

Удаляется корутина с бесконечным циклом `while(true) { delay(5.seconds); refreshConflicts() }`.  
Вместо нее:
1. `DaemonRuntime` подписывается на события `watcher.changes()` и при появлении локального события вызывает дебаунсированный `refreshConflicts()`.
2. По завершении каждого прогона синхронизации движка `DefaultSyncEngine` список конфликтов обновляется однократно.

---

### 4.6 Архитектура фоновой работы на Android 16

```
                           АРХИТЕКТУРА ANDROID 16
┌─────────────────────────────────────────────────────────────────────────────┐
│                            UI (MainActivity)                                │
│    Compose Multiplatform + Modifier.safeDrawingPadding() + Touch Targets    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
               ┌──────────────────────┴──────────────────────┐
               ▼                                             ▼
┌─────────────────────────────┐               ┌─────────────────────────────┐
│       Событийный запуск     │               │     Фоновое расписание      │
│  AndroidFileObserverWatcher │               │     WorkManager (Periodic)  │
│  (Локальные правки заметок) │               │     (15 мин / Wi-Fi / заряд)│
└──────────────┬──────────────┘               └──────────────┬──────────────┘
               │                                             │
               └──────────────────────┬──────────────────────┘
                                      ▼
                      ┌───────────────────────────────┐
                      │    MimirSyncWorker (Coroutine)│
                      │    Запуск DefaultSyncEngine   │
                      │    Execution Budget < 10 мин  │
                      └───────────────────────────────┘
```

1. **Замена `WatchService`**:
   В модуле `core` в наборе исходников `androidMain` реализуется `AndroidFileObserverVaultWatcher` поверх `android.os.FileObserver`, рекурсивно регистрирующий дескрипторы inotify для папок vault.
2. **Отказ от бесконечного Foreground Service**:
   Вместо сервиса с риском падения по 6-часовому таймауту Android 15/16 внедряется `androidx.work.CoroutineWorker`:
   - `PeriodicWorkRequestBuilder<MimirSyncWorker>(15, TimeUnit.MINUTES)` с ограничениями `setRequiredNetworkType(NetworkType.CONNECTED)` и опционально `setRequiresCharging(true)`.
   - При обнаружении локальных правок через `FileObserver` (пока приложение живо) или по нажатию кнопки запускается `OneTimeWorkRequestBuilder<MimirSyncWorker>().setExpedited(...)`.

---

## 5. План выполнения (Этапы реализации)

### Этап 1. Выделение `:client` и рефакторинг Ktor HTTP API
1. Создать Gradle-модуль `:client` в `settings.gradle.kts` и настроить convention-плагин `mimir.kmp-library`.
2. Перенести интерфейс `DaemonService` и DTO-модели из `:daemon` и `:app-desktop` в `:client`.
3. Реализовать KMP-версию `HttpDaemonClient` в `:client` с поддержкой явного статуса разрыва связи.
4. Добавить плагин `CORS` в `daemon/Routes.kt`.
5. Покрыть тестами сериализацию и CORS-запросы.

### Этап 2. Потоковый I/O и асинхронное шифрование
1. Добавить `RcloneCipher.create` (suspend-фабрика) и перевести вызовы в `DaemonRuntime` на фоновый диспетчер.
2. Переписать `GoogleDriveStorage.read` на потоковый `asSource()` над `ByteReadChannel`.
3. Написать интеграционный тест на скачивание 100 МБ потока с проверкой ограничения аллокации памяти.
4. Заменить `readByteArray()` в `resolveConflict` на потоковый `transferTo`.
5. Исключить 5-секундный таймер в `startConflictScan` в `DaemonRuntime`, заменив его на подписку на события `VaultWatcher`.

### Этап 3. Адаптация UI под мобильные экраны и Android 16
1. Добавить `Modifier.safeDrawingPadding()` в корневой `Surface` в `MimirApp.kt`.
2. Вынести шаги `SetupWizardScreen.kt` в отдельные файлы в `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/`.
3. Ввести адаптивные размеры сенсорных мишеней (минимум 48 dp на касание, компактные 32 dp на десктопе).

### Этап 4. Фундамент Android 16
1. Реализовать `AndroidFileObserverVaultWatcher` в `core/src/androidMain`.
2. Добавить интеграцию с `WorkManager` в `:app-android`.
3. Реализовать `AndroidKeystoreSecretStore` для безопасного хранения пароля vault и токенов Google OAuth.
4. Проверить сборку APK и валидацию манифеста на соответствие политикам безопасности Android 16.
