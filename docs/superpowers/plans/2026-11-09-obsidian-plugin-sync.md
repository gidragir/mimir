# Obsidian Plugin Sync — план реализации

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans — выполнять план по задачам. Шаги отмечаются чекбоксами (`- [ ]`).

**Goal:** плагин `apps/obsidian-plugin` сам синхронизирует vault ↔ Google Drive в формате Mimir на iOS, Android и desktop, передавая файлы любого размера потоком.

**Architecture:** композиция `libs/drive` + `libs/core` по образцу `apps/browser-extension/.../Popup.kt`. Obsidian-специфика — за тонкими Kotlin-интерфейсами (`RequestUrlFn`, `VaultFs`, `ResourceStreamFn`, `LocalStorageFn`), которые в `jsTest` подменяются фейками. Скачивание кусками по `Range` — стратегия в `GoogleDriveStorage`.

**Tech Stack:** Kotlin/JS (es2015), Ktor client core 3 (свой `HttpClientEngine`), kotlinx-coroutines, kotlinx-serialization, kotlinx-io, gradle `buildconfig`, Karma + ChromeHeadless.

**Spec:** `docs/superpowers/specs/2026-11-09-obsidian-plugin-sync-design.md`

## Global Constraints

- Зависимости только вниз: `apps/obsidian-plugin` → `libs/drive` → `libs/core`. В `libs/*` нет ничего про Obsidian.
- Рантайм плагина использует только модуль `obsidian` и Web API (`fetch`, `document`); `isDesktopOnly: false`.
- Идентификаторы (`mimir.obsidianPluginId`, `mimir.apiDefaultPort`, `mimir.version`) — только из `gradle.properties` через BuildConfig; литералами не дублируются.
- OAuth: Device-пара из `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID/SECRET`, fallback `MIMIR_GOOGLE_DEVICE_*`, иначе `""`. Сборка и тесты проходят без секретов.
- В JS ловится `Throwable`, `CancellationException` пробрасывается всегда.
- Тесты корутин: никогда `advanceUntilIdle()`; только `advanceTimeBy` и `scope.cancel()`.
- Строка «не подключено» — ровно `Mimir: not connected` (`SyncStatus.label()`).
- Исключения синхронизации: `<configDir>/**` и `**/*.mimir-tmp` всегда.
- Константы: кусок скачивания 8 МиБ (`8L * 1024 * 1024`); `parallelism` 2 по умолчанию, 1..4 в настройках; задержка после событий vault 5 с; повтор при возврате в приложение не чаще 30 с; таймер 5 мин; тайм-аут `DaemonProbe` 1 с.
- Коммиты — Conventional Commits на английском, `<type>(<scope>): <subject>`, scope `obsidian` / `drive` / `core`.
- `Dispatchers.Default` (в JS один поток); `Dispatchers.IO` не используется.

## Rulings (отклонения от спеки, выявленные при планировании)

- **Ruling 1:** `DefaultSyncEngine` не потребляет дельту change feed: каждый прогон — полный `listAll`. Как и в `SyncScheduler` (`libs/runtime/.../SyncScheduler.kt:192-220`), `getChanges` — дешёвая проверка перед прогоном: изменений в Drive нет и локальных изменений нет → прогон по таймеру/возврату пропускается. Прогон по событию vault, по команде и первый после загрузки выполняется всегда.
- **Ruling 2:** статус «нет сети» берётся из `SyncStatus.label()` = `Mimir: waiting for network` (единый текст для всех клиентов), а не `Mimir: offline` из спеки.
- **Ruling 3:** `EchoFilter` (`libs/core/.../fs/EchoFilter.kt`) становится `public`: плагин отсекает собственные записи тем же классом, без копии.
- **Ruling 4 (диагностика iPhone, Task 0):** iOS, mobile=true: `appendBinary` OK (4 МиБ), поток 300 МБ через `getResourcePath` OK, `requestUrl` + `Range` → httpbin 206/100 байт и Google 401 OK, `RcloneCipher.create` 277 мс. Ветвления спеки 4.7 не применяются: запись через `appendBinary`, чтение потоком, `Range`-скачивание кусками, лимит 50 МиБ не вводится. `performance.memory` на iOS недоступен (peakHeap=-1) — пик памяти не измерен.

## Review Focus

1. **Пользователь уходит из Obsidian посреди скачивания 300 МБ** → цель не повреждена (недописано только `*.mimir-tmp`), после возврата файл скачивается заново. Тест — Задача 4 (`writeCancelledLeavesTargetIntact`) и Задача 9 (`hiddenCancelsAndCleansTmp`).
2. **Пользователь переименовывает папку с сотней заметок** → один прогон, а не сто (склейка событий), без «удалить и загрузить заново» у записей самого плагина. Тест — Задача 9 (`burstOfVaultEventsCoalescesIntoOneRun`).
3. **Неверный пароль на втором устройстве** → ни одного `write`/`delete` в Drive и vault, понятный текст под полем. Тест — Задача 8 (`wrongPasswordWritesNothing`).
4. **Демон с `MimirTokenAuth` на том же десктопе** → плагин не синхронизирует, пока не включён переключатель. Тест — Задача 7 (`unauthorizedDaemonYields`).
5. **Имена с юникодом, пробелами и `#`** (`Заметки/Идея #1.md`) → `getResourcePath`/`requestUrl` не ломают путь. Тест — Задача 4 (`unicodeAndSpacesRoundTrip`).

Известное ограничение (не тест): `ConflictResolver` держит обе версии конфликтующего файла в памяти (`Conflict(local: ByteArray, remote: ByteArray)`). Конфликт двух огромных вложений на iPhone может уронить WebView — записать в README.

## Структура файлов

```
libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt     (изм.: downloadChunkBytes)
libs/drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageRangedReadTest.kt
libs/core/src/commonMain/kotlin/dev/mimir/core/fs/EchoFilter.kt            (изм.: public)
libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndexJson.kt       (перенос DTO из libs/runtime JsonSyncStateStore)
apps/obsidian-plugin/build.gradle.kts                                       (изм.: deps, buildconfig)
apps/obsidian-plugin/webpack.config.d/obsidian.js                           (изм.: тестовая заглушка)
apps/obsidian-plugin/scripts/check-plugin.mjs                               (изм.: desktop + mobile)
apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/
  ObsidianApi.kt            externals: requestUrl, Platform, Modal, Setting, PluginSettingTab, Workspace, Vault.on, DataAdapter+
  platform/Bridges.kt       RequestUrlFn, VaultFs, ResourceStreamFn, LocalStorageFn + реализации поверх obsidian
  net/ObsidianHttpEngine.kt
  vault/ObsidianLocalVault.kt
  state/ObsidianSyncStateStore.kt
  state/DeviceSecretStore.kt
  state/PluginSettings.kt
  daemon/DaemonProbe.kt
  sync/SyncAssembler.kt     граф движка, канарейка, вход
  sync/PluginSyncController.kt
  ui/PluginTexts.kt  ui/StatusView.kt  ui/MimirSettingTab.kt  ui/DeviceCodeModal.kt  ui/ConflictsModal.kt
  diag/DiagnosticsCommand.kt
  MimirPlugin.kt            composition root
apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/
  fakes/FakeVaultFs.kt  fakes/FakeRequestUrl.kt  fakes/FakeLocalStorage.kt
  <ClassName>Test.kt на каждый класс выше + EndToEndSyncTest.kt
```

---

### Задача 0: диагностика платформы на iPhone (spike, блокирующая)

**Files:**
- Create: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/diag/DiagnosticsCommand.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ObsidianApi.kt` (externals `requestUrl`, `RequestUrlParam`, `RequestUrlResponse`, `Platform`, `DataAdapter.appendBinary/getResourcePath/rename/remove/stat/list/write`)
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt` (команда `mimir-diagnostics`)
- Modify: `mise.toml` (задача `obsidian:deploy-dir`)

**Interfaces:**
- Produces: externals в `ObsidianApi.kt`, которые используют задачи 2–10.

- [x] **Step 1:** `suspend fun runDiagnostics(app: App): List<String>` — четыре проверки, по строке результата `OK`/`FAIL <причина>` на каждую:
  1. `appendBinary`: создать `mimir-diag.bin` (`writeBinary` 1 МиБ) → дописать 3 МиБ (`appendBinary`) → `stat.size == 4 MiB`, удалить.
  2. Поток: файл 300 МБ (создать `appendBinary`-кусками по 8 МиБ), прочитать `fetch(getResourcePath)` через `body.getReader()`, посчитать байты, отметить пик `performance.memory` (если есть).
  3. `requestUrl({url: "https://www.googleapis.com/drive/v3/about?fields=kind", headers: {Range: "bytes=0-9"}, throw: false})` — допустим ответ 401 (без токена): проверяется, что заголовок ушёл и статус пришёл; отдельно `https://httpbin.org/range/1024` с `Range: bytes=0-99` → 206 и 100 байт.
  4. `RcloneCipher.create("diagnostics")` — время в мс.
  Команда показывает результат в `Notice` (0 = без авто-скрытия) и пишет в `mimir-diagnostics.md` в корне vault.
- [x] **Step 2:** задача `obsidian:deploy-dir` в `mise.toml`: `assemblePlugin`, затем копия `apps/obsidian-plugin/build/plugin/*` в `${MIMIR_OBSIDIAN_DEPLOY_DIR:-build/obsidian-deploy}/<pluginId>/`; есть `run_windows`.
- [x] **Step 3:** `mise run obsidian:deploy-dir` → в каталоге есть `main.js` и `manifest.json`.
- [x] **Step 4:** коммит `feat(obsidian): add platform diagnostics command`.
- [x] **Step 5 (пользователь, iPhone):** положить каталог в `<vault>/.obsidian/plugins/` через «Файлы»/iCloud, включить плагин, выполнить «Mimir: diagnostics», прислать `mimir-diagnostics.md`.
- [x] **Step 6:** записать результат как `Ruling 4` в этот план и в `.superpowers/sdd/2026-11-09-obsidian-plugin-sync/progress.md`. Ветвление по спеке 4.7:
  - `appendBinary` FAIL → в задаче 4 запись через `writeBinary` целиком, лимит 50 МиБ на мобильных с `SyncProblemKind.WriteFailed` «too large for this Obsidian version»;
  - поток FAIL → `readBinary` с тем же лимитом;
  - `Range` FAIL → задача 1 остаётся, но плагин передаёт `downloadChunkBytes = null`, лимит 50 МиБ.
  Задачи 1–3 можно делать параллельно с ожиданием результата; задачи 4+ — после.

---

### Задача 1: скачивание кусками по Range в `GoogleDriveStorage`

**Files:**
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt:42-45` (конструктор), `:260-300` (`read`)
- Test: `libs/drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageRangedReadTest.kt`

**Interfaces:**
- Produces: `GoogleDriveStorage(client: DriveHttpClient? = null, pathResolver: DrivePathResolver? = null, downloadChunkBytes: Long? = null)`.

- [x] **Step 1: падающие тесты** (`MockEngine`, по образцу `GoogleDriveStorageStreamedTest`):
  - `rangedReadConcatenatesChunksInOrder`: файл 20 байт, `downloadChunkBytes = 8` → запросы `bytes=0-7`, `bytes=8-15`, `bytes=16-23`; ответы 206 с `Content-Range: bytes 0-7/20` и т.д.; `read().readByteArray()` равен исходным 20 байтам; ровно 3 GET `alt=media`.
  - `rangedReadStopsAtTotalFromContentRange`: файл ровно 16 байт → 2 запроса, не 3.
  - `rangedReadEmptyFile`: первый ответ `416` с `Content-Range: bytes */0` → пустой `Source`, 1 запрос.
  - `rangedRead200IgnoresRange`: сервер вернул 200 и всё тело → результат равен телу, 1 запрос.
  - `rangedReadRefreshesTokenMidFile`: второй кусок → 401, затем 206 → данные целы; второй кусок запрошен повторно с тем же `bytes=8-15` и новым токеном.
  - `rangedReadCancellationClosesChannel`: отмена потребителя после первого куска → больше запросов нет.
  - `nullChunkKeepsSingleStreamingGet`: `downloadChunkBytes = null` → 1 GET без заголовка `Range`.
- [x] **Step 2:** `mise run test:fast` → новые тесты падают (нет параметра).
- [x] **Step 3:** реализовать. При `downloadChunkBytes != null` насос в `streamScope` выполняет цикл: `from = 0`; GET с `Range: bytes=$from-${from + chunk - 1}` через `client.executeStreaming` → тело в `channel`; `total` из `Content-Range` (`bytes a-b/total`); `from += полученные байты`; стоп при `from >= total`, при 200 (сервер проигнорировал Range) или при 416. Ошибки и отмена — как в текущей ветке (`channel.cancel(e)`, `started.completeExceptionally`).
- [x] **Step 4:** `./gradlew :libs:drive:allTests` → PASS (jvm, js, wasmJs).
- [x] **Step 5:** коммит `feat(drive): add ranged chunked download to GoogleDriveStorage`.

---

### Задача 2: сборка плагина и HTTP-движок поверх `requestUrl`

**Files:**
- Modify: `apps/obsidian-plugin/build.gradle.kts` — `plugin.serialization`, `alias(libs.plugins.buildconfig)`; `jsMain`: `project(":libs:drive")`, `libs.ktor.client.core`, `libs.ktor.client.content.negotiation`, `libs.ktor.serialization.json`, `libs.serialization.json`; `jsTest`: `libs.ktor.client.mock`.
- BuildConfig `PluginBuildConfig` (пакет `dev.mimir.obsidian`): `PLUGIN_ID`, `VERSION`, `DAEMON_PORT` (из `mimir.apiDefaultPort`), `GOOGLE_DEVICE_CLIENT_ID`, `GOOGLE_DEVICE_CLIENT_SECRET` — функция `googleSecret` как в `apps/browser-extension/build.gradle.kts:10-12`.
- Create: `platform/Bridges.kt` (`RequestUrlFn`), `net/ObsidianHttpEngine.kt`
- Test: `net/ObsidianHttpEngineTest.kt`, `fakes/FakeRequestUrl.kt`

**Interfaces:**
- Produces:
  - `data class UrlRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)`
  - `data class UrlResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)` (ключи заголовков в нижнем регистре)
  - `fun interface RequestUrlFn { suspend fun invoke(request: UrlRequest): UrlResponse }`; `val obsidianRequestUrl: RequestUrlFn` (обёртка над external `requestUrl`, `throw: false`, `ArrayBuffer` ↔ `ByteArray`)
  - `class NetworkUnavailableException(cause: Throwable) : IOException` — бросается, когда `requestUrl` отклонён без статуса
  - `class ObsidianHttpEngine(request: RequestUrlFn) : HttpClientEngineBase("obsidian")`; `fun obsidianHttpClient(request: RequestUrlFn = obsidianRequestUrl): HttpClient` (с `ContentNegotiation` + `Json { ignoreUnknownKeys = true }`)

- [x] **Step 1: падающие тесты:**
  - `forwardsMethodHeadersAndBody`: POST с JSON-телом и `Authorization` → `FakeRequestUrl.lastRequest` содержит метод, заголовки и байты тела.
  - `mapsStatusHeadersAndBody`: фейк вернул 206, `content-range`, 3 байта → `response.status.value == 206`, заголовок читается `headers["Content-Range"]`, `bodyAsBytes()` — те же 3 байта.
  - `errorStatusIsNotThrownByEngine`: 404 → ответ со статусом 404, без исключения.
  - `rejectionWithoutStatusBecomesNetworkUnavailable`: фейк бросает `Throwable("net::ERR")` → `NetworkUnavailableException`.
  - `cancellationPropagates`: отмена вызывающей корутины → `CancellationException`, не `NetworkUnavailableException`.
- [x] **Step 2:** `./gradlew :apps:obsidian-plugin:jsBrowserTest` → FAIL.
- [x] **Step 3:** реализовать; тело запроса — `OutgoingContent` → `ByteArray` (`ByteArrayContent`, `ReadChannelContent`, `WriteChannelContent`, `NoContent`).
- [x] **Step 4:** проверить, что `DriveHttpClient` превращает `NetworkUnavailableException` в `RemoteUnavailableException` (тест `networkUnavailableMapsToRemoteUnavailable` здесь же, через `DriveHttpClient(obsidianHttpClient(fake), …)`). Если нет — маппинг добавить в `ObsidianHttpEngine` (бросать `RemoteUnavailableException` напрямую), не в `libs/drive`.
- [x] **Step 5:** тесты PASS; `mise run build` без секретов проходит.
- [x] **Step 6:** коммит `feat(obsidian): add Ktor engine over Obsidian requestUrl`.

---

### Задача 3: настройки и секреты на устройстве

**Files:**
- Create: `platform/Bridges.kt` (+`LocalStorageFn`), `state/DeviceSecretStore.kt`, `state/PluginSettings.kt`
- Test: `state/DeviceSecretStoreTest.kt`, `state/PluginSettingsTest.kt`, `fakes/FakeLocalStorage.kt`

**Interfaces:**
- Produces:
  - `interface LocalStorageFn { fun load(key: String): String?; fun save(key: String, value: String?) }` — реализация `AppLocalStorage(app)` поверх `app.loadLocalStorage`/`saveLocalStorage` (`null` удаляет).
  - `class DeviceSecretStore(storage: LocalStorageFn) : TokenStorage` + `suspend fun loadKey(): ByteArray?`, `suspend fun saveKey(key: ByteArray)`, `suspend fun clearAll()`. Ключи: `mimir.tokens` (JSON `OAuthTokens`), `mimir.key` (base64 80 байт), `mimir.canaryHash`, `mimir.changeToken`, плюс `suspend fun loadCanaryHash()/saveCanaryHash(String)`, `loadChangeToken()/saveChangeToken(String?)`.
  - `@Serializable data class PluginSettings(val rootFolder: String = "", val parallelism: Int = 2, val forceSyncWithDaemon: Boolean = false, val customClientId: String = "", val customClientSecret: String = "")`; `class PluginSettingsStore(load: suspend () -> dynamic, save: suspend (dynamic) -> Unit)` с `suspend fun get(): PluginSettings`, `suspend fun update(f: (PluginSettings) -> PluginSettings)`. `parallelism` обрезается до 1..4.

- [x] **Step 1: тесты:** `tokensRoundTrip`, `keyRoundTripIs80Bytes`, `clearAllRemovesEverything` (после — `load()`, `loadKey()`, `loadCanaryHash()`, `loadChangeToken()` все `null`), `corruptTokensJsonReturnsNull`, `settingsDefaults`, `parallelismClampedTo1to4` (`0 → 1`, `9 → 4`), `unknownFieldsIgnored`.
- [x] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS.
- [x] **Step 5:** коммит `feat(obsidian): store tokens and cipher key in device local storage`.

---

### Задача 4: `ObsidianLocalVault`

**Files:**
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/fs/EchoFilter.kt` (`internal` → `public`, Ruling 3)
- Create: `platform/Bridges.kt` (+`VaultFs`, `ResourceStreamFn`), `vault/ObsidianLocalVault.kt`
- Test: `vault/ObsidianLocalVaultTest.kt`, `fakes/FakeVaultFs.kt`

**Interfaces:**
- Produces:
  - `data class FsStat(val type: String /* "file"|"folder" */, val size: Long, val mtime: Long)`
  - `interface VaultFs { suspend fun list(dir: String): Pair<List<String>, List<String>> /* files, folders */; suspend fun stat(path: String): FsStat?; suspend fun writeBinary(path: String, data: ByteArray); suspend fun appendBinary(path: String, data: ByteArray); suspend fun readBinary(path: String): ByteArray; suspend fun rename(from: String, to: String); suspend fun remove(path: String); suspend fun mkdir(path: String); suspend fun exists(path: String): Boolean }` — `AdapterVaultFs(adapter: DataAdapter)`.
  - `fun interface ResourceStreamFn { suspend fun open(path: String): Source }` — `FetchResourceStream(adapter)`: `fetch(adapter.getResourcePath(path))`, тело через `ReadableStream` reader в `ByteChannel` → `Source`.
  - `sealed interface VaultEvent { data class Upsert(val path: String) : VaultEvent; data class Removed(val path: String) : VaultEvent; data class Renamed(val from: String, val to: String) : VaultEvent }`
  - `class ObsidianLocalVault(fs: VaultFs, resources: ResourceStreamFn, configDir: String, events: Flow<VaultEvent>, echo: EchoFilter, clock: Clock = Clock.System, writeChunkBytes: Int = 1 shl 20) : LocalVault`
  - `VaultFileId` = нормализованный относительный путь (как в `InMemoryLocalVault`).

- [x] **Step 1: падающие тесты** (на `FakeVaultFs` — in-memory дерево с `mtime`, журналом вызовов и кнопкой «упасть на N-м appendBinary»):
  - `listsFilesRecursivelyWithSizeAndMtime`
  - `excludesConfigDirAndTmpFiles`: `.obsidian/app.json`, `a/.b.md.mimir-tmp` отсутствуют в `list()`; `hiddenPaths() == setOf(".obsidian")`.
  - `writeGoesThroughTmpAndRename`: запись 2,5 МиБ → журнал `writeBinary(a/.b.md.mimir-tmp, 1MiB)`, `appendBinary ×2`, `rename(tmp → a/b.md)`; `mkdir("a")`, если папки нет.
  - `writeCancelledLeavesTargetIntact` (Review Focus 1): старое содержимое `a/b.md` = "old"; отмена во время второго `appendBinary` → `a/b.md` == "old", tmp удалён.
  - `writeFailureRemovesTmpAndRethrows`.
  - `readStreamsViaResourceFn`: `read()` вызывает `ResourceStreamFn`, а не `readBinary`.
  - `unicodeAndSpacesRoundTrip` (Review Focus 5): `Заметки/Идея #1.md` записывается, читается, перечисляется под тем же путём.
  - `deleteRemovesFile`; `deleteByPathMissingIsNoop`.
  - `changesMapsEventsAndSkipsEcho`: `Upsert` пути, записанного самим vault ≤ ttl назад, не выдаётся; `Renamed(a,b)` → `Deleted(a)` + `Created/Modified(b)`; события в `.obsidian/` не выдаются.
- [x] **Step 2:** FAIL → **Step 3:** реализовать; `write(relativePath, data)` читает `Source` блоками `writeChunkBytes`, перед `rename` делает `echo.mark(path)`; при ошибке/отмене — `remove(tmp)` в `NonCancellable`. Если у цели есть старый файл, `rename` на мобильном адаптере может не перезаписывать — сначала `remove(target)`, затем `rename` (порядок проверен тестом `writeReplacesExisting`).
- [x] **Step 4:** `./gradlew :apps:obsidian-plugin:jsBrowserTest :libs:core:allTests` → PASS.
- [x] **Step 5:** коммит `feat(obsidian): add LocalVault adapter over Obsidian DataAdapter` (вместе с `refactor(core)`-правкой `EchoFilter` в одном коммите допустимо).

---

### Задача 5: `ObsidianSyncStateStore`

**Files:**
- Create: `state/ObsidianSyncStateStore.kt`; Test: `state/ObsidianSyncStateStoreTest.kt`

**Interfaces:**
- Consumes: `VaultFs` (задача 4).
- Produces: `class ObsidianSyncStateStore(fs: VaultFs, pluginDir: String /* "<configDir>/plugins/<PLUGIN_ID>" */) : SyncStateStore`; файл `index.json`, временный `index.json.tmp`.

- [x] **Step 1: тесты:** `missingFileGivesEmptyIndex`, `saveThenLoadRoundTrip`, `saveIsAtomic` (журнал: `writeBinary(index.json.tmp)` → `remove(index.json)` → `rename`), `corruptJsonGivesEmptyIndexAndKeepsBackup` (битый файл переименован в `index.json.corrupt`).
- [x] **Step 2:** FAIL.
- [x] **Step 3: общий формат индекса.** `SyncIndex` не `@Serializable`; формат задают приватные `IndexDto`/`RecordDto` в `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt:38-60` (модуль jvm/android, недоступен из JS). Перенести их без изменения формата в `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndexJson.kt`: `object SyncIndexJson { fun encode(index: SyncIndex): String; fun decode(text: String): SyncIndex }`; в `libs/core/build.gradle.kts` добавить `kotlin("plugin.serialization")`. `JsonSyncStateStore` переходит на `SyncIndexJson` — `JsonSyncStateStoreTest` (jvm) проходит без изменений, это проверка совместимости формата. Тест `SyncIndexJsonTest` в `libs/core/commonTest`: round-trip записи со всеми полями, включая `remoteRevision = null`.
- [x] **Step 4:** реализовать `ObsidianSyncStateStore` поверх `SyncIndexJson` → `./gradlew :libs:core:allTests :libs:runtime:jvmTest :apps:obsidian-plugin:jsBrowserTest` → PASS.
- [x] **Step 5:** коммиты `refactor(core): share sync index JSON format across platforms` и `feat(obsidian): persist sync index in plugin directory`.

---

### Задача 6: вход через Device Code

**Files:**
- Create: `sync/SignIn.kt`; Test: `sync/SignInTest.kt`

**Interfaces:**
- Consumes: `obsidianHttpClient`, `DeviceSecretStore`, `PluginSettings`, `PluginBuildConfig`.
- Produces:
  - `fun deviceAuthClient(http: HttpClient, settings: PluginSettings): GoogleAuthClient?` — свой клиент из настроек, иначе `PluginBuildConfig`; `null`, если оба ID пусты.
  - `sealed interface SignInState { data object Idle; data object Requesting; data class AwaitingUser(val userCode: String, val verificationUrl: String, val expiresAt: Instant); data class Success(val email: String?); data object Expired; data class Failed(val message: String); data object Offline }`
  - `class DeviceSignIn(client: GoogleAuthClient, tokens: DeviceSecretStore, clock: Clock = Clock.System) { val state: StateFlow<SignInState>; suspend fun run(); fun cancel() }`
  - `suspend fun signOut(client: GoogleAuthClient?, tokens: DeviceSecretStore, state: ObsidianSyncStateStore)` — отзыв токена (ошибка отзыва не мешает очистке), `clearAll()`, пустой индекс.

- [x] **Step 1: тесты** (`MockEngine` на `oauth2.googleapis.com`, как в `GoogleAuthClientTest`): `happyPathSavesTokensAndEmail`, `pollsWithServerInterval` (`interval = 7` → опросы через 7 с, `advanceTimeBy`), `expiredCodeGivesExpired`, `accessDeniedGivesFailed`, `networkErrorGivesOffline`, `noClientConfiguredReturnsNull`, `signOutClearsEvenIfRevokeFails`.
- [x] **Step 2:** FAIL → **Step 3:** реализовать поверх существующего Device Code API `GoogleAuthClient` (не дублировать HTTP) → **Step 4:** PASS.
- [x] **Step 5:** коммит `feat(obsidian): add device code sign-in flow`.

---

### Задача 7: `DaemonProbe`

**Files:**
- Create: `daemon/DaemonProbe.kt`; Test: `daemon/DaemonProbeTest.kt`

**Interfaces:**
- Consumes: `RequestUrlFn`.
- Produces: `sealed interface DaemonPresence { data object Absent; data object ServesThisVault; data object ServesOtherVault; data object Unauthorized }`; `class DaemonProbe(request: RequestUrlFn, port: Int, isDesktop: Boolean, vaultBasePath: String?, timeout: Duration = 1.seconds) { suspend fun check(): DaemonPresence }`.
- Правило «уступать» (в контроллере, задача 9): `ServesThisVault` → всегда; `Unauthorized` → если `!settings.forceSyncWithDaemon`.

- [x] **Step 1: тесты:** `notDesktopIsAbsentWithoutRequest`, `noResponseIsAbsent`, `timeoutIsAbsent` (фейк висит > 1 с), `matchingVaultPath` (нормализация: `/home/u/Vault/` == `/home/u/Vault`; на Windows `C:\U\Vault` == `c:/U/Vault`), `otherVaultPath`, `unauthorizedDaemonYields` (401 → `Unauthorized`; Review Focus 4), URL = `http://127.0.0.1:$port/api/v1/status`.
- [x] **Step 2:** FAIL → **Step 3:** реализовать (`withTimeoutOrNull`; `vaultPath` из JSON `/status`) → **Step 4:** PASS.
- [x] **Step 5:** коммит `feat(obsidian): detect Mimir daemon serving the same vault`.

---

### Задача 8: сборка движка и проверка пароля (`SyncAssembler`)

**Files:**
- Create: `sync/SyncAssembler.kt`; Test: `sync/SyncAssemblerTest.kt`

**Interfaces:**
- Consumes: задачи 1–6.
- Produces:
  - `sealed interface KeySetup { data class Ok(val firstDevice: Boolean); data class WrongPassword(val message: String); data class Failed(val message: String) }`
  - `class SyncAssembler(http: HttpClient, secrets: DeviceSecretStore, settings: PluginSettingsStore, vault: LocalVault, stateStore: SyncStateStore, remoteFactory: (DriveHttpClient, String) -> RemoteStorage = { c, root -> GoogleDriveStorage(c, DrivePathResolver(c, root), downloadChunkBytes = 8L * 1024 * 1024) })`
    - `suspend fun setupKey(password: String, salt: String?): KeySetup` — `RcloneCipher.create` → `CanaryVerifier.verifyReadOnly(remote, cipher)`; `Valid` → `saveKey`, `saveCanaryHash`; пустая папка → создать `.mimir_canary` (`CANARY_MAGIC`, зашифрованный) и `firstDevice = true`.
    - `suspend fun isReady(): Boolean` — есть токены, ключ, непустой `rootFolder`.
    - `suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>?` — `null`, если не готов; `EncryptedRemoteStorage(remote, RcloneCipher.fromKeyBytes(key), reservedRootNames = setOf(CanaryVerifier.CANARY_FILENAME))`; `DefaultSyncEngine(vault, encrypted, stateStore, parallelism = settings.parallelism, dispatcher = Dispatchers.Default)`.
  - Плагинный `GoogleDriveStorage` создаётся через `remoteFactory` — в тестах `InMemoryRemoteStorage`.

- [x] **Step 1: тесты** (`InMemoryRemoteStorage`, `InMemoryLocalVault`, `FakeLocalStorage`):
  - `emptyFolderCreatesCanaryAndSavesKey`
  - `correctPasswordOnSecondDevice`
  - `wrongPasswordWritesNothing` (Review Focus 3): Drive с канарейкой другого пароля → `WrongPassword`, `loadKey() == null`, в `InMemoryRemoteStorage` и vault ни одной записи/удаления.
  - `notReadyWithoutTokensOrFolder` → `buildEngine() == null`.
  - `engineSyncsRoundTrip`: заметка в vault → `syncNow()` → в remote есть зашифрованный путь из трёх сегментов для `a/b/c.md`.
- [x] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS (scrypt медленный в JS: при необходимости `mocha-timeout.js` уже даёт 120 с).
- [x] **Step 5:** коммит `feat(obsidian): assemble encrypted sync engine and verify vault password`.

---

### Задача 9: `PluginSyncController`

**Files:**
- Create: `sync/PluginSyncController.kt`; Test: `sync/PluginSyncControllerTest.kt`

**Interfaces:**
- Consumes: `SyncAssembler`, `DaemonProbe`, `DeviceSecretStore` (change token), `Flow<VaultEvent>`.
- Produces:
  - `enum class Trigger { Startup, Resume, VaultChange, Timer, Manual }`
  - `sealed interface PluginState { data object NotConnected; data object YieldsToDaemon; data object PausedForDaemon /* Unauthorized */; data class Syncing(val done: Int, val total: Int); data class UpToDate(val at: Instant, val problems: List<SyncProblem>, val conflicts: List<String>); data object Offline; data class NeedsSignIn(val message: String); data class Error(val message: String) }`
  - `class PluginSyncController(scope: CoroutineScope, assembler: SyncAssembler, probe: DaemonProbe, secrets: DeviceSecretStore, settings: PluginSettingsStore, vaultEvents: Flow<VaultEvent>, clock: Clock = Clock.System) { val state: StateFlow<PluginState>; fun trigger(t: Trigger); fun onVisibilityChanged(visible: Boolean); fun start(); fun stop() }`
- Поведение (константы — Global Constraints):
  - один прогон за раз; триггеры во время прогона → ровно один повторный прогон после;
  - `VaultChange` — через 5 с тишины; `Resume` — не чаще 30 с; `Timer` — 5 мин, только при `visible`;
  - Ruling 1: для `Timer`/`Resume` сначала `getChanges(token)` (или `getLatestChangeToken`, если токена нет — тогда прогон); пусто и нет отложенных локальных событий → пропуск; токен сохраняется после успешного прогона; ошибка ленты → прогон;
  - перед прогоном `probe.check()` → `YieldsToDaemon` / `PausedForDaemon`;
  - `onVisibilityChanged(false)` → отмена текущего прогона (`engine.stop()` + отмена job), таймер стоп;
  - `conflicts` = `progress.completed.filter { it.action == SyncItemAction.Conflict }.map { it.path }`.
  - Маппинг ошибок — спека 4.5: `RemoteUnavailableException` → `Offline`; `DriveAuthException` 401 → `NeedsSignIn`; `SyncAbortedException` → `Error(PluginTexts.abortedNothingDeleted)`; прочий `Throwable` → `Error(message)`.

- [x] **Step 1: тесты** (фейковый `SyncAssembler` через интерфейс `EngineSource { suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>? }` — выделить его в этой задаче; фейковый `SyncEngine` со счётчиком `syncNow`; `StandardTestDispatcher`, только `advanceTimeBy`, в конце `scope.cancel()`):
  - `startupRunsOnce`
  - `burstOfVaultEventsCoalescesIntoOneRun` (Review Focus 2): 100 событий за 3 с → через 5 с ровно 1 `syncNow`.
  - `triggerDuringRunCausesExactlyOneRerun`
  - `resumeThrottledTo30s`
  - `timerSkipsWhenNoRemoteChanges` (Ruling 1) и `timerRunsWhenRemoteChanged`
  - `timerStopsWhenHidden`
  - `hiddenCancelsAndCleansTmp` (Review Focus 1): прогон висит → `onVisibilityChanged(false)` → `engine.stop()` вызван, job отменён, состояние не `Syncing`.
  - `notReadyIsNotConnectedWithoutNetwork`
  - `daemonServingVaultYields`, `unauthorizedDaemonPausesUnlessForced`
  - `offlineMapsToOffline`, `authErrorMapsToNeedsSignIn`, `abortedMapsToError`
- [x] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS.
- [x] **Step 5:** коммит `feat(obsidian): add sync controller with foreground triggers`.

---

### Задача 10: UI и composition root

**Files:**
- Create: `ui/PluginTexts.kt`, `ui/StatusView.kt`, `ui/MimirSettingTab.kt`, `ui/DeviceCodeModal.kt`, `ui/ConflictsModal.kt`
- Modify: `ObsidianApi.kt` (externals `Modal`, `Setting`, `PluginSettingTab`, `Workspace.onLayoutReady`, `Vault.on`, `Plugin.registerEvent/registerInterval/addRibbonIcon/addSettingTab`, `moment.locale`), `MimirPlugin.kt`, `webpack.config.d/obsidian.js` (тестовая заглушка дополняется пустыми `Modal`, `Setting`, `PluginSettingTab`, `requestUrl`, `Platform`), `scripts/check-plugin.mjs`
- Test: `ui/PluginTextsTest.kt`, `ui/StatusViewTest.kt`

**Interfaces:**
- Consumes: всё выше.
- Produces:
  - `class PluginTexts(locale: String)` (`"ru*"` → русский, иначе английский). Строки статуса:
    - `NotConnected` → `SyncStatus.NotConfigured.label()` (`Mimir: not connected`);
    - `Syncing(d, t)` → `Mimir: syncing $d/$t`;
    - `UpToDate` → `Mimir: up to date · <относительное время>`; при конфликтах `Mimir: N conflicts`;
    - `Offline` → `SyncStatus.Offline.label()` (Ruling 2);
    - `YieldsToDaemon` → `Mimir: handled by Mimir Desktop`;
    - `PausedForDaemon` → `Mimir: paused — Mimir Desktop is running` / `Mimir: приостановлено — запущен Mimir Desktop`;
    - `abortedNothingDeleted` → `Drive is empty but the sync index is not — sync stopped, nothing was deleted` / русский текст спеки.
  - `fun statusLabel(state: PluginState, texts: PluginTexts, now: Instant): String` в `StatusView.kt` (чистая функция — тестируется).

- [ ] **Step 1: тесты:** `statusLabel` для каждого `PluginState` на en и ru; `notConnectedIsExactLabel`; `relativeTime` («just now», «2 min ago», «2 мин назад»).
- [ ] **Step 2:** FAIL → **Step 3:** реализовать `PluginTexts`/`statusLabel` → PASS.
- [ ] **Step 4:** UI-компоненты (родные компоненты Obsidian, без своих цветов):
  - `MimirSettingTab`: блок «Аккаунт» (кнопка входа → `DeviceCodeModal` / email + «Выйти»); блок «Папка Drive и пароль» (активен после входа; поля папки, пароля, соли; кнопка «Проверить» → `setupKey`, результат строкой под полем, во время scrypt — «Проверяю пароль…» и неактивная кнопка); блок «Синхронизация» (активен после ключа; слайдер параллельности 1–4, «Синхронизировать сейчас», время последнего прогона, список `problems`, переключатель `forceSyncWithDaemon` — виден только в состоянии `PausedForDaemon`); блок «Свой OAuth-клиент» свёрнут, раскрыт, если `PluginBuildConfig.GOOGLE_DEVICE_CLIENT_ID` пуст.
  - `DeviceCodeModal`: рендер по `SignInState` — 5 состояний спеки 4.4; кнопка «Скопировать код и открыть» (`navigator.clipboard.writeText` + `window.open(verificationUrl)`); таймер до `expiresAt`; закрытие модалки → `cancel()`.
  - `ConflictsModal`: список путей, клик → `app.workspace.openLinkText(path, "")`.
- [ ] **Step 5:** `MimirPlugin.onload`: собрать граф (`obsidianHttpClient` → `DeviceSecretStore(AppLocalStorage(app))` → `PluginSettingsStore(::loadData, ::saveData)` → `ObsidianLocalVault(AdapterVaultFs, FetchResourceStream, vault.configDir, events из vault.on(...) через callbackFlow, EchoFilter(10.seconds, Clock.System))` → `ObsidianSyncStateStore` → `SyncAssembler` → `DaemonProbe(obsidianRequestUrl, PluginBuildConfig.DAEMON_PORT, Platform.isDesktopApp, adapter.basePath)` → `PluginSyncController`); статус-бар (desktop) или ribbon-иконка (`Platform.isMobile`) с подписью статуса и кликом → `trigger(Manual)` (при конфликтах — `ConflictsModal`); команда «Mimir: Sync now»; `onLayoutReady` → `start()` + `trigger(Startup)`; `document.addEventListener("visibilitychange")` через `registerDomEvent`; первый запуск без настроек → `Notice` с кнопкой открыть настройки. `StubSyncEngine` удалить из плагина; `PluginUpdater` сохранить. `onunload` → `controller.stop()`, `scope.cancel()`.
- [ ] **Step 6:** `check-plugin.mjs`: загрузка `main.js` с фейковым `obsidian` дважды — `Platform.isMobile = false` (ожидается статус-бар с `Mimir: not connected`) и `true` (ожидается ribbon-иконка, без исключений); `./gradlew :apps:obsidian-plugin:check` → PASS.
- [ ] **Step 7:** проверка глазами на desktop: `mise run obsidian:deploy-dir` с `MIMIR_OBSIDIAN_DEPLOY_DIR=<тестовый vault>/.obsidian/plugins`, Obsidian (светлая и тёмная тема): настройки — все блоки и состояния, модалка входа, статус. Скриншоты в PR.
- [ ] **Step 8:** коммит `feat(obsidian): add settings, sign-in modal and status UI`.

---

### Задача 11: сквозной тест, документация, проверка на устройствах

**Files:**
- Test: `apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/EndToEndSyncTest.kt`
- Modify: `README.md` (раздел плагина Obsidian: установка через BRAT/вручную, ограничения: только при открытом приложении, localStorage без изоляции от других плагинов, конфликт огромных вложений держит обе версии в памяти, не держать vault в iCloud), `AGENTS.md` (строка про плагин: «не `isDesktopOnly`, синхронизирует сам, уступает демону; Obsidian API — за `VaultFs`/`RequestUrlFn`»), база context-init.

- [ ] **Step 1: тест** `EndToEndSyncTest`: два `ObsidianLocalVault` на двух `FakeVaultFs` + общий `InMemoryRemoteStorage`, реальный `SyncAssembler` и `PluginSyncController` для каждого, один пароль:
  - создание на A → появляется на B;
  - изменение на B → на A;
  - одновременное изменение → копия конфликта, `UpToDate.conflicts` не пуст;
  - удаление на A → удаление на B;
  - очистка remote при непустом индексе → `Error(abortedNothingDeleted)`, файлы B на месте.
- [ ] **Step 2:** PASS; `mise run ci:fast` → PASS; `mise run build` без секретов и без Android SDK → PASS.
- [ ] **Step 3:** `rtk context-init update`, `rtk context-init check --compact` → без дрейфа.
- [ ] **Step 4:** коммит `docs(obsidian): document standalone plugin sync and limitations`.
- [ ] **Step 5 (пользователь, устройства):** сценарий спеки §6:
  - демон пишет заметку + вложение 300 МБ → iPhone получает, Obsidian не падает;
  - правка на iPhone → демон получает;
  - одновременная правка → копия конфликта;
  - Android — эмулятор (Device Hub) или телефон.
  Результаты — в `progress.md`; дефекты — отдельные задачи.

## Отчёт об исполнении (заполняется в конце)

Повторяет «Инварианты Mimir» спеки §5 с фактическим результатом: Платформы (что проверено на iPhone, Android, desktop), Дизайн (какие порты и адаптеры появились), IO (замеренная память на 300 МБ, число вызовов Drive на прогон без изменений), UX (скриншоты состояний).
