# Mimir — этап 3: адаптер Google Drive и авторизация (Google Drive Storage Adapter & OAuth Flow)

Дата: 2026-10-23  
Статус: согласовано  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md` (§6.4, §6.4a). При конфликте приоритет у родительской спеки.  
Спека этапа 1 (криптография): `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md`.  
Спека этапа 2 (движок синхронизации): `docs/superpowers/specs/2026-10-23-mimir-sync-design.md`.

---

## 1. Контекст и архитектурные границы

Этап 1 (`RcloneCipher`, `EncryptedRemoteStorage`) и Этап 2 (`SyncEngine`, `ConflictResolver`, `SyncIndex`) реализованы в `core` и протестированы на JVM, JS и Wasm.

Этап 3 реализует интеграцию с облачным хранилищем Google Drive внутри модуля `drive`:
- Реализация порта `RemoteStorage` поверх Google Drive REST API v3 через Ktor HTTP Client (чистый Kotlin KMP: jvm, android, js, wasmJs).
- Никаких проприетарных Google Cloud SDK (`com.google.api-client` запрещен).
- Непосредственное взаимодействие с Google Drive через `drive.file` scope (приложение видит только созданные им файлы).
- Расширение контрактов `core` интерфейсом `ChangeFeedStorage` с поддержкой дешифрования в `EncryptedRemoteStorage` и эмуляцией в `InMemoryRemoteStorage`.
- Реализация переносимого OAuth 2.0 клиента: хранение токенов, автоматический refresh токена при HTTP 401, потоки авторизации (PKCE Loopback для Desktop, RFC 8628 Device Authorization Grant для headless-демона, абстракция для браузерного расширения).
- Все тесты запускаются в `commonTest` модуля `drive` через `Ktor MockEngine` без внешних вызовов.

---

## 2. Архитектурные решения (Best Practices)

### 2.1. Контракт Changes Feed (`ChangeFeedStorage`)

Вместо загрязнения базового интерфейса `RemoteStorage` или жесткой привязки к Google Drive:
1. В `core` вводится интерфейс:
   ```kotlin
   interface ChangeFeedStorage : RemoteStorage {
       suspend fun getLatestChangeToken(): String
       suspend fun getChanges(sinceToken: String): RemoteChangesResult
   }

   data class RemoteChange(
       val path: RemotePath,
       val isDeleted: Boolean,
       val entry: RemoteEntry?,
   )

   data class RemoteChangesResult(
       val changes: List<RemoteChange>,
       val nextToken: String,
   )
   ```
2. `EncryptedRemoteStorage` реализует `ChangeFeedStorage`, если его `inner` реализует `ChangeFeedStorage`. При получении `RemoteChangesResult` пути расшифровываются через `cipher.decryptName`.
3. `InMemoryRemoteStorage` реализует `ChangeFeedStorage` для юнит-тестов движка синхронизации.
4. `GoogleDriveStorage` реализует `ChangeFeedStorage` поверх `drive.changes.getStartPageToken` и `drive.changes.list`.

### 2.2. Маппинг иерархии путей на графовую структуру Google Drive

В Google Drive файлы и каталоги идентифицируются `fileId` и имеют список `parents`. Пути как сущности отсутствуют, и в одной папке могут существовать несколько объектов с одинаковым именем.

1. **Корневая директория**:
   - При инициализации `GoogleDriveStorage` производит поиск папки с именем `rootFolderName` (по умолчанию `"Mimir"`) в корне Google Drive (`'root' in parents and name = 'Mimir' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`).
   - Если папка отсутствует, она создается. Ее `fileId` становится базовым якорем хранилища.
2. **Двусторонний кэш путей (`DrivePathResolver`)**:
   - Хранит соответствие `RemotePath <-> fileId` и `fileId <-> DriveNodeMetadata`.
   - Разрешение пути `a/b/c.md`:
     1. Если `a/b/c.md` есть в кэше, возврат `fileId`.
     2. Иначе поиск/создание `a` в корне, затем `b` в `a`, затем поиск `c.md` в `b`.
   - Инвалидация кэша: удаление файла/каталога удаляет соответствующие ветки кэша; при получении изменений через Changes Feed кэш актуализируется.
3. **Разрешение дубликатов имен**:
   - Если при листинге или поиске в папке найдено более одного активного (не в корзине) объекта с одним именем:
     - Выбирается объект с наибольшим `modifiedTime`.
     - Дубликаты помещаются в корзину (`trashed = true`) или удаляются, предотвращая рассинхронизацию.

### 2.3. Потоковая передача данных (Upload / Download)

1. **Download**:
   - `GET https://www.googleapis.com/drive/v3/files/{fileId}?alt=media`
   - Чтение потока через `ByteReadChannel` ответа Ktor с адаптером в `kotlinx.io.Source`.
2. **Upload (двухуровневая стратегия)**:
   - **Simple Upload** (для файлов < 5 МБ):
     - `POST /upload/drive/v3/files?uploadType=multipart` для создания нового файла.
     - `PATCH /upload/drive/v3/files/{fileId}?uploadType=multipart` для обновления существующего.
     - Одноэтапный запрос, передающий JSON-метаданные и бинарное тело.
   - **Resumable Upload** (для файлов ≥ 5 МБ):
     - Инициализация сессии: `POST /upload/drive/v3/files?uploadType=resumable` (получение `Location` URI сессии).
     - Передача чанками, кратными 256 КБ (рекомендация Google: 256 КБ * N, дефолт 1 МБ).
     - При сетевом сбое: запрос текущего смещения `PUT` с `Content-Range: bytes */totalLength`, получение статуса `308 Resume Incomplete` со смещением `Range: bytes=0-K`, продолжение передачи с байта `K+1`.

### 2.4. Управление токенами и OAuth 2.0

1. **Модель учетных данных**:
   ```kotlin
   @Serializable
   data class OAuthTokens(
       val accessToken: String,
       val refreshToken: String?,
       val expiresAtEpochSeconds: Long,
       val scope: String,
       val tokenType: String = "Bearer",
   ) {
       fun isExpired(clock: Clock, skewSeconds: Long = 60): Boolean =
           clock.now().epochSeconds >= (expiresAtEpochSeconds - skewSeconds)
   }
   ```
2. **Перехватчик Ktor (Token Refresh Interceptor)**:
   - Проверка `isExpired()` перед отправкой запроса.
   - При истечении или при получении `HTTP 401 Unauthorized`:
     - Синхронизированный через `Mutex` вызов обновления токена по `refresh_token` (`https://oauth2.googleapis.com/token`).
     - Сохранение новых токенов в `TokenStorage`.
     - Повтор оригинального запроса с новым `Authorization: Bearer <token>`.
3. **Потоки аутентификации (`OAuthFlowHandler`)**:
   - `DeviceAuthorizationFlow` (RFC 8628):
     - Полностью KMP-совместимый поток (чистый HTTP).
     - Запрос `POST https://oauth2.googleapis.com/device/code` (получение `user_code`, `verification_url`, `device_code`).
     - Поллинг `POST https://oauth2.googleapis.com/token` с интервалом `interval` секунд до подтверждения пользователем.
     - Идеально подходит для `daemon` / Docker и headless окружений.
   - `PkceLoopbackFlow` (JVM Desktop):
     - Генерация `code_verifier` и `code_challenge` (S256).
     - Старт временного Ktor HTTP-сервера на случайном порту `127.0.0.1`.
     - Открытие системного браузера с URL Google OAuth.
     - Прием редиректа с `code`, обмен кода на токены, возврат HTML-страницы успеха в браузер.
   - `ExternalWebFlowHandler` (JS/Wasm / WebExtension):
     - Абстракция над вызовом `chrome.identity.launchWebAuthFlow`.

---

## 3. Спецификация REST API Google Drive v3

### Эндпоинты

| Действие | Метод | URL | Параметры / Тело |
|---|---|---|---|
| Поиск файлов | `GET` | `/drive/v3/files` | `q`, `fields`, `pageSize`, `pageToken` |
| Метаданные файла | `GET` | `/drive/v3/files/{id}` | `fields=id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed` |
| Скачивание | `GET` | `/drive/v3/files/{id}` | `alt=media` |
| Создание метаданных | `POST` | `/drive/v3/files` | JSON: `name`, `parents`, `mimeType` |
| Multipart Upload | `POST` / `PATCH` | `/upload/drive/v3/files` | `uploadType=multipart` |
| Инициализация Resumable | `POST` / `PATCH` | `/upload/drive/v3/files` | `uploadType=resumable` |
| Загрузка чанка | `PUT` | `{resumableUri}` | `Content-Range: bytes START-END/TOTAL` |
| Удаление (Trash) | `PATCH` | `/drive/v3/files/{id}` | `{"trashed": true}` |
| Окончательное удаление | `DELETE` | `/drive/v3/files/{id}` | — |
| Стартовый токен изменений | `GET` | `/drive/v3/changes/startPageToken` | `supportsAllDrives=false` |
| Список изменений | `GET` | `/drive/v3/changes` | `pageToken`, `fields`, `includeRemoved=true` |

---

## 4. Граничные случаи и отказоустойчивость

1. **HTTP 429 Too Many Requests / 503 Service Unavailable**:
   - Экспоненциальный backoff с джиттером: задержка $2^n \times 100 \text{ мс} \pm 20\%$, максимум 5 попыток.
2. **Параллельное создание одной папки**:
   - При гонке потоков или процессов, создающих директорию `foo`: если после создания обнаружен дубликат, один экземпляр переименовывается/удаляется, а кэш фиксирует выживший ID.
3. **Обрыв сети при Resumable Upload**:
   - Перехват исключений ввода-вывода Ktor.
   - Запрос текущего состояния загрузки через `PUT` с пустым телом и заголовком `Content-Range: bytes */size`.
   - Возобновление со смещения, указанного сервером в заголовке `Range: bytes=0-N`.
4. **Отказ в авторизации (Revoked Refresh Token)**:
   - Ошибка `invalid_grant` транслируется в типизированное исключение `AuthRevokedException`, сигнализирующее UI о необходимости повторного входа.

---

## 5. Зависимости модуля `drive`

В `gradle/libs.versions.toml`:
```toml
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
```

В `drive/build.gradle.kts`:
- `commonMain`: `api(project(":core"))`, `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-json`.
- `commonTest`: `ktor-client-mock`, `coroutines-test`.
