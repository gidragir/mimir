# Стоимость IO синхронизации — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** стоимость прогона синхронизации растёт от числа изменений, а не от размера vault: план без сетевых чтений, потоки, параллельное исполнение с пределом, повторы Drive по правилам квот.

**Architecture:** план → исполнение сохраняется; `SyncPlanBuilder` решает по метаданным и локальному хэшу, новое действие `Reconcile` сверяет содержимое при исполнении одним скачиванием; `DefaultSyncEngine` исполняет фазы (метаданные → переносы → удаления) пулом воркеров, индекс — через `IndexUpdater`; Drive получает `RetryPolicy`, потоковую отправку и мьютекс создания папок.

**Tech Stack:** Kotlin Multiplatform (jvm, js, wasmJs, android), kotlinx.coroutines 1.10.2, kotlinx-io, Ktor 3.6 (`MockEngine`), `kotlinx-coroutines-test` (`runTest`).

**Spec:** `docs/superpowers/specs/2026-10-24-mimir-sync-io-design.md`

## Global Constraints

- Сборка: `mise run build` зелёная без Android SDK и без секретов; тесты `core`/`drive` — `./gradlew :core:allTests :drive:allTests` (все цели, не только JVM).
- `core`/`drive` commonMain: никакого `Dispatchers.IO`, `runBlocking`, `Thread.sleep`, `GlobalScope`; диспетчер — параметр.
- Тесты пауз и параллельности — на виртуальном времени `runTest`, без реальных `delay`.
- Пакеты `dev.mimir.<module>.<area>`; версии зависимостей — только `gradle/libs.versions.toml`.
- Коммиты conventional (`feat(core): …`), каждый с трейлером `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Строка UI для сверки — ровно `Сверяю с Google Drive: <путь>`.
- Значения по умолчанию: `parallelism = 4` (допустимо 1..16), `indexSaveEvery = 100`, `RetryPolicy(maxAttempts = 5, baseDelay = 1.seconds, maxDelay = 32.seconds)`, порог resumable 5 МБ, блок 1 МиБ, env `MIMIR_SYNC_PARALLELISM`.

## Review Focus

1. Пустой файл (0 байт) — `Upload`/`Download`/`HashingSource`/`DriveUploader` дают правильный SHA-256 пустой строки и один multipart-запрос; добавлено в задачи 1 и 4.
2. Файл ровно на пороге 5 МБ и ровно кратный 1 МиБ — последний блок resumable несёт итоговый размер, нет пустого лишнего блока; задача 4.
3. Отмена прогона посреди параллельных переносов — индекс сохраняется (`flush` в `NonCancellable`), статус `Idle`; задача 7.
4. `Retry-After` больше `maxDelay` или в прошлом (HTTP-дата) — ожидание `maxDelay` / 0, не отрицательное; задача 2.
5. Переименование файла (удаление старого пути + новый файл) при параллельности — новая копия отправлена до удаления старой; задача 7.

## Rulings

- **Ruling 1:** `IndexUpdater` — новый класс в `core/sync` поверх `SyncStateStore` (паттерн «Репозиторий + атомарная запись» не меняется, добавляется пакетирование записей).
- **Ruling 2:** исчерпанный rate limit/5xx → `RemoteUnavailableException`: прогон останавливается целиком (статус `Offline`), а не плодит проблемы по файлам.
- **Ruling 3:** `SyncProgress.current` при параллельности — последний начатый элемент; `upcoming` — следующие ещё не начатые.
- **Ruling 4:** совпадение содержимого в `Reconcile` не попадает в `completed` и не считается проблемой, но увеличивает `done`.
- **Ruling 5:** `ConflictResolver.resolveConflict(details, localSource)` — параметр `remoteSource` удаляется (не использовался реализацией по умолчанию).

---

### Task 1: Инкрементальный SHA-256 и `HashingSource`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/crypto/Sha256.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/HashingSource.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/HashTest.kt` (дополнить), `core/src/commonTest/kotlin/dev/mimir/core/HashingSourceTest.kt`

**Interfaces — Produces:**
- `class Sha256.Hasher { fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset); fun digest(): ByteArray; fun digestHex(): String }` (повторный `digest` после завершения — `IllegalStateException`).
- `fun Sha256.digest(data: ByteArray): ByteArray` — без изменений, через `Hasher`.
- `fun ByteArray.toHex(): String` — публичная утилита в `crypto` (заменяет приватные копии в движке и vault).
- `class HashingSource(upstream: RawSource) : RawSource { fun hashHex(): String }` — `hashHex()` до исчерпания upstream бросает `IllegalStateException`; `close()` закрывает upstream.
- `suspend fun Source.sha256Hex(): String` — потоковый хэш блоками по 64 КиБ, источник закрывается.

- [ ] **Step 1: Failing tests:** `incrementalMatchesOneShotAcrossBlockBoundaries` — для длин 0, 1, 55, 56, 63, 64, 65, 1000, 200_000 и разбиения на куски по 1/7/64/65_536 байт `Hasher` == `digest`; `emptyInputHash` == `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; `HashingSourceTest.passesBytesThroughAndHashes` — байты на выходе равны входу, `hashHex()` == `digest(...).toHex()`; `hashHexBeforeExhaustionFails`.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests '*Hash*'` — FAIL (нет `Hasher`).
- [ ] **Step 3:** реализовать; `Hasher` держит 64-байтовый блок и счётчик длины, сжатие вынести из `digest` в приватную функцию.
- [ ] **Step 4:** `./gradlew :core:allTests` — PASS.
- [ ] **Step 5:** commit `feat(core): incremental SHA-256 and hashing source`.

### Task 2: `RetryPolicy` в `DriveHttpClient`

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/client/RetryPolicy.kt`
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt`, вызовы `executeWithRetry(maxRetries = …)` в `drive/`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/client/DriveHttpClientTest.kt`

**Interfaces — Produces:**
- `data class RetryPolicy(val maxAttempts: Int = 5, val baseDelay: Duration = 1.seconds, val maxDelay: Duration = 32.seconds) { fun backoff(attempt: Int, random: Random): Duration }` — `d = min(maxDelay, baseDelay * 2^(attempt-1))`, результат в `[d/2, d]`.
- `DriveHttpClient(httpClient, tokenStorage, authClients, clock = Clock.System, retryPolicy = RetryPolicy(), random = Random.Default)`; `suspend fun executeWithRetry(block: suspend (token: String) -> HttpResponse): HttpResponse` (параметр `maxRetries` удаляется).
- Внутренние: `parseRetryAfter(header: String?, now: Instant): Duration?` (секунды или RFC 1123 дата; прошедшая дата → `Duration.ZERO`); `isRateLimit403(response): Boolean` — тело `{"error":{"errors":[{"reason":…}]}}`, reason ∈ {`rateLimitExceeded`, `userRateLimitExceeded`}.

- [ ] **Step 1: Failing tests** (`runTest`, `MockEngine`, `currentTime` для проверки пауз):
  - `retriesOn429HonouringRetryAfterSeconds` — `Retry-After: 7` → вторая попытка ровно через 7 с виртуального времени.
  - `retryAfterHttpDateIsRelativeToClock` и `retryAfterAboveMaxDelayIsCapped` (120 → 32 с), `retryAfterInPastMeansNoWait`.
  - `retriesOn403UserRateLimitExceeded` (тело Drive с reason) → успех со 2-й попытки; `doesNotRetry403InsufficientPermissions` — 1 запрос, ответ 403 возвращён.
  - `backoffIsJitteredWithinBounds` — `Random(42)`, паузы попыток 1..4 в `[d/2, d]` для d = 1, 2, 4, 8 с.
  - `exhausted429ThrowsRemoteUnavailable` и `exhausted5xxThrowsRemoteUnavailable` — ровно `maxAttempts` запросов.
  - существующие тесты 401-refresh и сетевых ошибок — без изменений поведения.
- [ ] **Step 2:** `./gradlew :drive:jvmTest --tests '*DriveHttpClientTest*'` — FAIL.
- [ ] **Step 3:** реализовать; тело ответа 403 читать `bodyAsText()` (ответ сохранённый, читается повторно вызывающим).
- [ ] **Step 4:** `./gradlew :drive:allTests` — PASS.
- [ ] **Step 5:** commit `feat(drive): jittered retries honouring Retry-After and 403 rate limits`.

### Task 3: Мьютекс создания папок в `DrivePathResolver`

**Files:**
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt` (`resolveOrCreateFolderInternal`)
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/path/DrivePathResolverTest.kt`

**Interfaces — Produces:** поведение `resolveOrCreateFolderPath(dir)` без изменения сигнатуры; при `createIfMissing = true` весь проход по сегментам — под `folderCreationMutex` (отдельный от `mutex` кэша, чтобы не было самоблокировки).

- [ ] **Step 1: Failing test** `concurrentCreationOfSameFolderCreatesItOnce` — `MockEngine`, где поиск папки отвечает пусто до создания, а `POST /files` с `mimeType` папки считается; две корутины `async { resolveOrCreateFolderPath(RemotePath("a/b")) }` с `yield` внутри обработчика → ровно 2 POST (по одному на сегмент), оба вызова вернули одинаковый id.
- [ ] **Step 2:** запустить — FAIL (4 POST).
- [ ] **Step 3:** реализовать.
- [ ] **Step 4:** `./gradlew :drive:allTests` — PASS.
- [ ] **Step 5:** commit `fix(drive): serialize folder creation so parallel uploads share one folder`.

### Task 4: Потоковая отправка `DriveUploader`

**Files:**
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/upload/DriveUploaderTest.kt`

**Interfaces — Consumes:** `executeWithRetry(block)` из задачи 2. **Produces:** `upload(path, data: Source): RemoteEntry` — без `readByteArray()` всего источника.

Алгоритм: прочитать в `Buffer` до `resumableThresholdBytes`; если `data.exhausted()` — multipart (как сейчас). Иначе resumable без `X-Upload-Content-Length`; цикл: держим текущий блок (`chunkSize`, ≤ 1 МиБ) в памяти, `last = data.exhausted()` после его заполнения; `Content-Range: bytes a-b/*`, у последнего — `bytes a-b/<total>`; 308 с `Range: bytes=0-x`, где x < b, — дослать хвост текущего блока с x+1; восстановление — `Content-Range: bytes */*` (или `*/<total>`, если total уже известен).

- [ ] **Step 1: Failing tests:** `largeUploadStreamsChunksWithUnknownTotal` — 2.5 МиБ сверх порога (порог в тесте 1 МиБ, блок 256 КиБ): все PUT кроме последнего с `/*`, последний `/<total>`, сумма тел = источник; `exactMultipleOfChunkHasNoEmptyTrailingChunk`; `emptyFileUsesSingleMultipart`; `partialAckResendsTailOfCurrentChunk` (308 `Range: bytes=0-100` → следующий PUT начинается с 101); существующие тесты — зелёные.
- [ ] **Step 2:** запустить — FAIL.
- [ ] **Step 3:** реализовать.
- [ ] **Step 4:** `./gradlew :drive:allTests` — PASS.
- [ ] **Step 5:** commit `feat(drive): stream uploads in chunks without buffering the whole file`.

### Task 5: Диспетчер в `FileSystemLocalVault`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt`
- Test: `core/src/jvmTest/kotlin/dev/mimir/core/fs/FileSystemLocalVaultTest.kt`

**Interfaces — Produces:** `FileSystemLocalVault(rootPath: Path, rules: SyncRules = SyncRules(), watcher: VaultWatcher? = null, ioDispatcher: CoroutineDispatcher = Dispatchers.Default)`; `list`, `hiddenPaths`, `getEntry`, `read` (проверки и открытие), `write`, `delete`, `deleteByPath` — в `withContext(ioDispatcher)`; приватный `toHex` заменить на `ByteArray.toHex()` из задачи 1.

- [ ] **Step 1: Failing test** `operationsRunOnInjectedDispatcher` — тестовый `class CountingDispatcher : CoroutineDispatcher()` с `isDispatchNeeded = true`, `dispatch` увеличивает `dispatches` и выполняет `block.run()`; для каждой из операций `list`, `hiddenPaths`, `getEntry`, `read`, `write`, `deleteByPath` — `dispatches` вырос после вызова.
- [ ] **Step 2:** запустить — FAIL.
- [ ] **Step 3:** реализовать.
- [ ] **Step 4:** `./gradlew :core:allTests` — PASS.
- [ ] **Step 5:** commit `feat(core): inject the IO dispatcher into FileSystemLocalVault`.

### Task 6: План без удалённых чтений, `Reconcile`, потоки в `apply`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlanBuilder.kt`, `SyncPlan.kt`, `ConflictResolver.kt`, `DefaultSyncEngine.kt`, `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncProgress.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SyncTexts.kt` (ветка `Verify`)
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncPlanBuilderTest.kt`, `ConflictResolverTest.kt`, `SyncEngineProgressTest.kt`, `SyncEngineIntegrationTest.kt`; создать `core/src/commonMain/kotlin/dev/mimir/core/testing/CountingRemoteStorage.kt` и `core/src/commonTest/kotlin/dev/mimir/core/SyncEngineIoBudgetTest.kt`

**Interfaces — Consumes:** `HashingSource`, `Source.sha256Hex()`, `ByteArray.toHex()` (задача 1).
**Produces:**
- `SyncPlanBuilder.buildPlan(local, remote, index, localHash: suspend (LocalEntry) -> String, hidden = emptySet(), onReconcileFailure = null): SyncPlan`.
- `SyncAction.Reconcile(path: String, localEntry: LocalEntry, remoteEntry: RemoteEntry, baseRecord: FileSyncRecord?)`; `SyncAction.Conflict` удаляется из плана (исход `Reconcile`).
- `enum SyncItemAction { Upload, Download, DeleteLocal, DeleteRemote, Conflict, Verify }`; `SyncTexts.actionNow` → `"Сверяю с Google Drive: ${item.path}"`.
- `ConflictResolver.resolveConflict(details: ConflictDetails, localSource: Source): ConflictOutcome` (Ruling 5).
- `class CountingRemoteStorage(inner: RemoteStorage) : RemoteStorage` в `dev.mimir.core` (`core/testing`): счётчики `listAllCalls`, `listCalls`, `statCalls`, `readCalls`, `writeCalls`, `deleteCalls` (`Int`, `var … private set`), `fun reset()`; `listAll` делегирует в `inner.listAll`.

Правила плана — таблица §3 спеки дословно; `remoteChanged` по `revision` при наличии у обеих сторон, иначе по size+mtime; изменился только mtime при той же ревизии → `UpdateMetadata`. Исполнение `Reconcile` — §4 спеки. В `DefaultSyncEngine` нет `readByteArray()`; `Upload`/`Download` идут через `HashingSource`.

- [ ] **Step 1: Failing tests:**
  - `SyncPlanBuilderTest`: перевести на `localHash`; строки таблицы §3 по одной: `bothExistWithoutRecordReconciles`, `remoteOnlyChangedDownloadsWithoutHashing` (вычислитель не вызывается), `onlyRemoteMtimeChangedWithSameRevisionUpdatesMetadata`, `bothChangedLocalEqualsBaseDownloads`, `bothChangedLocalDiffersReconciles`, `localDeletedRemoteChangedDownloads`; удалить тесты, проверявшие удалённый хэш в плане.
  - `SyncEngineIoBudgetTest` (N = 50, `CountingRemoteStorage(InMemoryRemoteStorage())`, `InMemoryLocalVault`): `unchangedRunReadsAndWritesNothing` (после первого прогона и `reset()`: `listAllCalls == 1`, `readCalls == statCalls == writeCalls == 0`); `localEditsOnlyWrite` (K = 3 → `writeCalls == 3`, `readCalls == 0`); `remoteEditsReadOnce` (K = 3 → `readCalls == 3`); `lostIndexWithIdenticalFilesReadsEachOnceAndWritesNothing` (новый `InMemorySyncStateStore` → `readCalls == 50`, `writeCalls == 0`, локальных записей 0, индекс снова из 50 записей); `realConflictReadsTwiceWritesOnce` (`readCalls == 2`, `writeCalls == 1`, копия конфликта есть локально и удалённо); `reconcileWhereRemoteEqualsBaseUploads`.
  - `ConflictResolverTest`, `SyncEngineProgressTest`, `SyncEngineIntegrationTest` — под новую сигнатуру; прогресс `Reconcile` показывает `Verify`, в `completed` — `Conflict` для конфликта.
  - Пустой файл: `emptyFileRoundTrip` — загрузка и скачивание пустого файла, `baseHash` == хэш пустой строки.
- [ ] **Step 2:** `./gradlew :core:jvmTest` — FAIL.
- [ ] **Step 3:** реализовать план, `Reconcile` и потоки (исполнение пока последовательное).
- [ ] **Step 4:** `./gradlew :core:allTests :ui:desktopTest :daemon:test` — PASS.
- [ ] **Step 5:** commit `feat(core): plan without remote reads and reconcile content once while applying`.

### Task 7: Параллельное исполнение и `IndexUpdater`

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/sync/IndexUpdater.kt`
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt` (при росте > 300 строк вынести исполнение действий в `core/sync/ActionExecutor.kt`)
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncEngineParallelTest.kt`, `IndexUpdaterTest.kt`, дополнить `SyncEngineIoBudgetTest.kt`

**Interfaces — Produces:**
- `class IndexUpdater(store: SyncStateStore, initial: SyncIndex, saveEvery: Int = 100) { val current: SyncIndex (snapshot); suspend fun update(transform: (SyncIndex) -> SyncIndex); suspend fun flush() }` — `flush` сохраняет, только если были несохранённые изменения.
- `DefaultSyncEngine(localVault, remoteStorage, stateStore, conflictResolver = DefaultConflictResolver(), rules = SyncRules(), clock = Clock.System, parallelism: Int = 4, dispatcher: CoroutineDispatcher = Dispatchers.Default, indexSaveEvery: Int = 100)`; `require(parallelism in 1..16)`.

Фазы и ошибки — §6 спеки: `UpdateMetadata` → переносы (`Upload`/`Download`/`Reconcile`) → удаления; воркеры читают `Channel`, каждое действие `withContext(dispatcher)`; `flush()` в `finally` под `NonCancellable`; прогресс и проблемы — через `_progress.update`.

- [ ] **Step 1: Failing tests:**
  - `IndexUpdaterTest`: `savesEveryNChangesAndOnFlush` (250 обновлений, `saveEvery = 100` → 2 сохранения + 1 во `flush`); `flushWithoutChangesDoesNotSave`.
  - `SyncEngineParallelTest` (`StandardTestDispatcher` как `dispatcher`, фейк-удалённое с `CompletableDeferred`-воротами и счётчиком одновременных вызовов): `transfersNeverExceedParallelism` (20 загрузок, `parallelism = 3` → максимум 3); `deletesStartOnlyAfterAllTransfers` (переименование: `DeleteRemote` старого пути начинается после завершения `write` нового); `deleteLocalStillChecksStat` (отстающий список → файл не удалён); `fatalErrorCancelsOtherWorkersAndGoesOffline` (одна `write` бросает `RemoteUnavailableException` → остальные отменены, статус `Offline`, индекс сохранён `flush`); `cancellationFlushesIndex`; `finalIndexHasAllRecords` (`parallelism = 4`, 50 файлов).
  - `SyncEngineIoBudgetTest.indexSavesAreBatched` — счётный `SyncStateStore`: 250 загрузок → не больше 3 + 1 сохранений.
  - Все существующие тесты движка проходят без изменений с параметрами по умолчанию.
- [ ] **Step 2:** `./gradlew :core:jvmTest` — FAIL.
- [ ] **Step 3:** реализовать.
- [ ] **Step 4:** `./gradlew :core:allTests` — PASS.
- [ ] **Step 5:** commit `feat(core): apply independent sync actions in parallel with batched index saves`.

### Task 8: Проводка в демоне, документация, changeset

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt`, `DaemonRuntime.kt`, `README.md` (переменные окружения, если там есть таблица `MIMIR_*`), `AGENTS.md` (строка про параллельность, если есть подходящий раздел), `.claude/skills/mimir-sync-io/SKILL.md` («Известный долг» → оставшиеся пункты §10 спеки и changeFeed; эталоны: `IndexUpdater`, `RetryPolicy`, `CountingRemoteStorage`)
- Create: `.changeset/sync-io-parallel-streaming.md`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonConfigTest.kt`

**Interfaces — Consumes:** конструкторы из задач 5 и 7. **Produces:** `DaemonConfig.syncParallelism: Int = 4`; env `MIMIR_SYNC_PARALLELISM` (целое 1..16, иначе `IllegalArgumentException("MIMIR_SYNC_PARALLELISM must be a number from 1 to 16, got '<v>'")`); `DaemonRuntime` создаёт `FileSystemLocalVault(…, ioDispatcher = Dispatchers.IO)` и `DefaultSyncEngine(…, parallelism = config.syncParallelism, dispatcher = Dispatchers.IO)`.

- [ ] **Step 1: Failing tests:** `syncParallelismFromEnv`, `syncParallelismOutOfRangeFails` (`0`, `17`, `abc`), значение по умолчанию 4 и сохранение в `config.json` как у `syncIntervalSeconds`.
- [ ] **Step 2:** `./gradlew :daemon:test --tests '*DaemonConfigTest*'` — FAIL.
- [ ] **Step 3:** реализовать проводку; changeset — все пакеты группы `fixed` `minor`, как `.changeset/desktop-polish.md`, текст: синхронизация не скачивает файлы для сравнения, работает параллельно и потоками, соблюдает лимиты Google Drive.
- [ ] **Step 4:** `mise run build` — зелёная, в выводе `Android SDK not found: skipping Android targets` (если SDK нет); `pnpm run version:check` — без рассинхрона.
- [ ] **Step 5:** commit `feat(daemon): configurable sync parallelism on the IO dispatcher` и `chore: add changeset for sync IO`.

## Инварианты Mimir

- **Платформы:** изменения в `core`/`drive` commonMain собираются для jvm, js, wasmJs (android — при SDK); `Dispatchers.IO` передаётся только из `daemon` (JVM). Windows/macOS/Android — CI-матрица, руками не проверяются.
- **Дизайн:** план → исполнение (`SyncPlanBuilder` → `SyncAction.Reconcile` → `DefaultSyncEngine`); декоратор-фейк `CountingRemoteStorage` по образцу `EncryptedRemoteStorage`; `IndexUpdater` поверх репозитория `SyncStateStore` (Ruling 1); `RetryPolicy` — значение-параметр `DriveHttpClient`; composition root — `DaemonRuntime`.
- **IO:** бюджет — таблица §11 спеки, закреплён `SyncEngineIoBudgetTest`; параллельно переносы и удаления с пределом 4 (1..16); блокирующая работа на `ioDispatcher`/`dispatcher` (демон — `Dispatchers.IO`).
- **UX:** одна новая строка прогресса `Сверяю с Google Drive: <путь>`; исчерпанный лимит — статус `Offline` (Ruling 2); экраны не меняются.
