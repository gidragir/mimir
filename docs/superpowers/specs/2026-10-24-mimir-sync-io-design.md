# Mimir — стоимость IO синхронизации: параллельность, потоки, повторы

Дата: 2026-10-24  
Статус: согласовано  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md`; спеки движка — `2026-10-23-mimir-sync-design.md`, Drive — `2026-10-23-mimir-drive-design.md`, демона — `2026-10-24-mimir-daemon-design.md`. При конфликте приоритет у родительской.  
Правила: скилл `mimir-sync-io` (раздел «Известный долг» закрывается этой спекой).

---

## 1. Замысел

Движок синхронизации корректен, но его стоимость растёт от размера vault, а не от числа изменений:

- `SyncPlanBuilder` скачивает удалённый файл только чтобы посчитать хэш; `apply` скачивает его ещё раз. При потере `index.json` (ручной выход из `SyncAbortedException`) или первой синхронизации скопированного руками vault скачивается **весь** vault.
- `apply` и хэширование читают файлы целиком в `ByteArray`; `DriveUploader` тоже.
- Действия исполняются строго по одному; индекс (`index.json`, мегабайты на 10 000 файлов) перезаписывается целиком после **каждого** действия — O(N²) записанных байт.
- `FileSystemLocalVault` блокирует вызывающий поток; диспетчер не внедряется.
- `DriveHttpClient.executeWithRetry`: пауза 100/200 мс без джиттера, `Retry-After` и 403 `rateLimitExceeded`/`userRateLimitExceeded` не учитываются.
- Опрос `getChanges` только будит полный прогон с `listAll`.

Цель — стоимость прогона пропорциональна числу изменений K, память — размеру блока, а не файла; квоты Drive соблюдаются.

## 2. Цели и не-цели

**Цели (этот этап):**
1. План без сетевых чтений; содержимое сравнивается только при исполнении и не более одного раза.
2. Потоки вместо `ByteArray` в движке и в отправке `DriveUploader`.
3. Параллельное исполнение независимых действий с настраиваемым пределом; удаления — после переносов; индекс под `Mutex`, сохраняется пакетами.
4. Диспетчер блокирующего IO — параметр `FileSystemLocalVault` и `DefaultSyncEngine`.
5. Повторы Drive с джиттером, `Retry-After` и 403 rate limit.
6. Тесты бюджета запросов.

**Не-цели:**
- Инкрементальный прогон по changeFeed — только дизайн (§9), реализация отдельным планом.
- Потоковое **скачивание** в `GoogleDriveStorage.read` (`body<ByteArray>()`): для потока нужен порт вида `read(path) { source -> … }` — меняет все адаптеры и декоратор; остаётся известным долгом (§10).
- Сравнение по md5 шифротекста с nonce из заголовка (Range-чтение 32 байт, повторное шифрование локального файла) — возможная оптимизация `Reconcile`, вне этапа.
- Изменение UI прогресса под несколько текущих файлов: `current` остаётся одним элементом (последний начатый).

## 3. План без чтения удалённых файлов

`SyncPlanBuilder.buildPlan(local, remote, index, localHash: suspend (LocalEntry) -> String, hidden, onReconcileFailure)` — вычислитель хэша только локальный. Удалённое содержимое на этапе плана не читается никогда.

**Изменение удалённого файла** определяется метаданными:

- если у записи индекса и у `RemoteEntry` есть `revision` (у Drive — `md5Checksum` шифротекста; из-за случайного nonce rclone-crypt новая запись почти всегда даёт новый md5): `remoteChanged = revision != base.remoteRevision || size != base.remoteSize`;
- иначе (адаптер без ревизий): `remoteChanged = size != base.remoteSize || modifiedAt != base.remoteModifiedAt`.

Если `remoteChanged == false`, но `modifiedAt` отличается — `UpdateMetadata` без чтений.

**Таблица решений** (L — локальный файл, R — удалённый, B — запись индекса, `h(L)` — хэш локального):

| L | R | B | Условие | Действие |
|---|---|---|---|---|
| есть | нет | нет | — | `Upload` |
| нет | есть | нет | — | `Download` |
| есть | есть | нет | — | `Reconcile(base = null)` |
| есть | есть | есть | ничего не изменилось | `NoOp` (или `UpdateMetadata`, если сменился только mtime удалённого) |
| есть | есть | есть | изменён только L | `h(L) == B.hash` → `UpdateMetadata`, иначе `Upload` |
| есть | есть | есть | изменён только R | `Download` |
| есть | есть | есть | изменены оба | `h(L) == B.hash` → `Download`, иначе `Reconcile(base = B)` |
| нет | есть | есть | R изменён | `Download` (воскрешение — безопасная сторона) |
| нет | есть | есть | R не изменён | `DeleteRemote` |
| есть | нет | есть | L изменён и `h(L) != B.hash` | `Upload` |
| есть | нет | есть | иначе | `DeleteLocal` (с проверкой `stat` при исполнении) |

Отличия от прежней таблицы и почему они безопасны:
- «Изменён только R» раньше скачивал файл для хэша и при совпадении с базой делал `UpdateMetadata`; теперь сразу `Download` — одно скачивание вместо одного-двух, худший случай — локальная перезапись тем же содержимым.
- «L удалён, R изменён» с `hash(R) == base` раньше давал `DeleteRemote`; теперь `Download`. Ревизия меняется только при новой записи содержимого, случай редкий; ошибка направлена в сторону сохранения файла.
- `SyncAction.Conflict` план больше не создаёт: конфликт обнаруживается только в `Reconcile`.

## 4. `Reconcile` при исполнении

`SyncAction.Reconcile(path, localEntry, remoteEntry, baseRecord: FileSyncRecord?)`:

1. Скачать удалённый файл потоком через SHA-256, ничего не записывая → `hR`. Посчитать `hL` потоком по локальному файлу.
2. `hR == hL` → только запись индекса (как `UpdateMetadata`); в `completed` не попадает.
3. `baseRecord != null && hR == baseRecord.baseHash` → исполнить как `Upload`.
4. Иначе — конфликт, порядок прежний: (а) локальная копия конфликта из локального содержимого, (б) второе скачивание удалённого файла в основной путь, (в) отправка копии конфликта. Результат в `completed` — `Conflict`.

Стоимость: совпадение — 1 скачивание; настоящий конфликт — 2 (раньше 3: хэш в плане, `resolveConflict`, «свежее» чтение).

`ConflictResolver.resolveConflict(details, localSource)` теряет неиспользуемый параметр `remoteSource`, чтобы не скачивать удалённый файл до решения.

**Прогресс/UI:** новый `SyncItemAction.Verify`; текст в `SyncTexts` — «Сверяю с Google Drive: <путь>». Пункт `upcoming`/`current` для `Reconcile` — `Verify`; `completed` получает фактический исход (`Upload` или `Conflict`; совпадение не попадает в `completed`).

## 5. Потоки

- `Sha256`: инкрементальный `Sha256.Hasher` (`update(bytes, offset, length)`, `digestHex()`); `digest(ByteArray)` остаётся обёрткой.
- `HashingSource(upstream: Source)`: `RawSource`, пропускающий байты через `Hasher`; после полного чтения — `hashHex()`.
- `Upload`: `remote.write(path, HashingSource(vault.read(id)).buffered())`; хэш записи индекса — из `HashingSource`.
- `Download`: `vault.write(path, HashingSource(remote.read(path)).buffered())`.
- Локальный хэш в плане и в `Reconcile` — потоковым чтением блоками по 64 КиБ.
- В `DefaultSyncEngine` нет `readByteArray()`.
- `DriveUploader.upload(path, data)`: читает до порога `resumableThresholdBytes` (5 МБ) в `Buffer`; если источник исчерпан — multipart как сейчас; иначе resumable-сессия без `X-Upload-Content-Length`, блоки `chunkSize` (1 МиБ, кратно 256 КиБ) с `Content-Range: bytes a-b/*`, последний — `bytes a-b/<total>`. В памяти — только текущий блок; ответ 308 с `Range` меньше отправленного — досылается хвост текущего блока; восстановление по `bytes */*`.

## 6. Параллельное исполнение и индекс

`DefaultSyncEngine(…, parallelism: Int = 4, dispatcher: CoroutineDispatcher = Dispatchers.Default, indexSaveEvery: Int = 100)`.

Фазы исполнения плана:
1. **Метаданные:** все `UpdateMetadata` применяются к индексу без IO.
2. **Переносы:** `Upload`, `Download`, `Reconcile` — `parallelism` воркеров читают действия из `Channel`; каждое действие — `withContext(dispatcher)`.
3. **Удаления:** `DeleteRemote`, `DeleteLocal` — начинаются после завершения всех переносов (переименование = удаление + отправка: новая копия появляется до удаления старой), тем же пулом. `DeleteLocal` по-прежнему делает `stat` непосредственно перед удалением в той же корутине.

Ошибки: `CancellationException`, `RemoteUnavailableException`, `RemoteAuthException` в любом воркере отменяют остальных (`coroutineScope`) и пробрасываются; прочие — в `problems`, прогон продолжается. Прогресс обновляется атомарно (`MutableStateFlow.update`).

**`IndexUpdater`** (`core/sync`): текущий `SyncIndex` под `Mutex`, `update { index -> … }`, `save` после каждых `indexSaveEvery` изменений и `flush()` в конце прогона (в `finally`, `NonCancellable`). Если процесс упал до сохранения — у путей нет записей, следующий прогон даёт `Reconcile` (1 скачивание на путь, без потерь).

**Папка до содержимого в Drive:** `DrivePathResolver.resolveOrCreateFolderPath` выполняет «найти → создать» под отдельным `folderCreationMutex`, иначе два параллельных `Upload` в новую папку создадут две одноимённые папки.

**Настройка:** `DaemonConfig.syncParallelism` (по умолчанию 4, env `MIMIR_SYNC_PARALLELISM`, 1..16); `DaemonRuntime` передаёт его и `Dispatchers.IO` в движок.

## 7. Диспетчеры

- `FileSystemLocalVault(rootPath, rules, watcher, ioDispatcher: CoroutineDispatcher = Dispatchers.Default)`: `list`, `hiddenPaths`, `getEntry`, `read` (открытие и проверка), `write`, `delete*` — внутри `withContext(ioDispatcher)`. В common-коде `Dispatchers.IO` нет (JS/wasm) — отсюда параметр; JVM/Android-вызовы передают `Dispatchers.IO`.
- `DefaultSyncEngine.dispatcher` — где исполняются действия: ленивое чтение потоков (блокирующее чтение файла), SHA-256 и шифрование выполняются в корутине-потребителе. Демон передаёт `Dispatchers.IO`.
- `DaemonRuntime` убирает ручную обёртку `withContext(Dispatchers.IO)` вокруг `vault.list()` там, где vault уже получил диспетчер.

## 8. Повторы запросов к Drive

`RetryPolicy(maxAttempts = 5, baseDelay = 1.s, maxDelay = 32.s)` и `random: Random = Random.Default` — параметры `DriveHttpClient`.

- Повторяемые ответы: 429; 5xx; 403, у которого в теле `error.errors[].reason` — `rateLimitExceeded` или `userRateLimitExceeded`. Прочие 403 (нет прав, `storageQuotaExceeded`) возвращаются вызывающему без повтора.
- Пауза попытки n (с 1): `d = min(maxDelay, baseDelay·2^(n-1))`, ожидание `d/2 + random(0..d/2)` (equal jitter). Если есть `Retry-After` (секунды или HTTP-дата относительно `clock`) — ждать `min(maxDelay, Retry-After)`.
- Попытки исчерпаны: 5xx и rate limit → `RemoteUnavailableException` (прогон останавливается целиком, планировщик повторит на следующем тике, а не превращает тысячи файлов в проблемы).
- 401 — один принудительный refresh токена, как сейчас.
- Существующие вызовы `executeWithRetry(maxRetries = …)` переходят на политику.

## 9. Инкрементальный прогон по changeFeed (дизайн, отдельный план)

- Токен ленты хранится рядом с индексом (`SyncIndex.changeToken`), выставляется после успешного полного прогона (`getLatestChangeToken` до `listAll`, чтобы изменения во время листинга не потерялись).
- Инкрементальный прогон при `capabilities.changeFeed`, наличии токена и рабочем watcher: удалённая карта = записи индекса (как `RemoteEntry`) + применённые `getChanges(token)`; локальная — полный `list()` (локально дёшево). Новый токен сохраняется вместе с индексом в конце.
- Полный `listAll` обязателен: нет токена, токен отвергнут (`DriveHttpException` 400/404/410), была ошибка прошлого прогона, сломан watcher, прошло `fullReconcileInterval` (по умолчанию 6 ч), изменения вне корня/с неразрешимым путём.
- Защита от пустого списка (`SyncAbortedException`) и `stat` перед `DeleteLocal` действуют и в инкрементальном режиме.
- Выбор режима — стратегия по `capabilities`, не по типу хранилища.

## 10. Остающийся долг

- `GoogleDriveStorage.read` держит шифротекст файла в памяти (`body<ByteArray>()`); `EncryptedRemoteStorage.read` расшифровывает потоком поверх него.
- UI показывает один текущий файл при нескольких параллельных.

## 11. Инварианты Mimir

**Платформы.** Код в `core/commonMain` и `drive/commonMain`: JVM (desktop, демон, Docker), Android, JS/wasm. `Dispatchers.IO` не используется в common — только как значение, передаваемое из `daemon`/JVM. `Channel`, `Mutex`, `coroutineScope` доступны на всех целях. Windows/macOS/Android руками не проверяются — CI-матрица.

**Дизайн.** План → исполнение (`SyncPlanBuilder` → `SyncAction` → `DefaultSyncEngine.apply`): новое действие `Reconcile` в sealed `SyncAction`, решение по метаданным отдельно от эффектов. Репозиторий состояния — `SyncStateStore`; `IndexUpdater` — пакетная запись поверх него (`Ruling:` в плане). Тестовый двойник — `CountingRemoteStorage` в `core/testing` (декоратор над `RemoteStorage`, как `EncryptedRemoteStorage`). Повторы — `RetryPolicy` как параметр-значение `DriveHttpClient` (эталон: параметры-функции `SystemBrowserOpener`). Внедрение через конструктор в composition root `DaemonRuntime`.

**IO.** Для vault из N файлов (Drive содержит ≈N объектов) и K изменений:

| Прогон | Запросы к Drive | Скачано | Локально прочитано |
|---|---|---|---|
| Без изменений | `listAll`: ⌈N/1000⌉ страниц; 0 `read`/`write`/`stat` | 0 | 0 |
| K локальных правок | ⌈N/1000⌉ + K отправок (+ запросы путь→id) | 0 | ≤ 2 потоковых чтения на файл |
| K удалённых правок | ⌈N/1000⌉ + K скачиваний | K файлов | 0 |
| Сменился только `modifiedTime` | ⌈N/1000⌉ | 0 | 0 |
| K локальных удалений-зеркал (`DeleteLocal`) | ⌈N/1000⌉ + K `stat` | 0 | 0 |
| Потерян индекс, файлы совпадают | ⌈N/1000⌉ + N скачиваний | N (вместо N…2N) | N |
| Настоящий конфликт | +2 скачивания, +1 отправка | 2 | 3 (хэш локального, источник копии, копия для отправки) |
| Запись `index.json` | — | — | ⌈A/100⌉ + 1 записей вместо A (A — число действий) |

Параллельно: переносы и удаления, предел `parallelism` (4, 1..16). Блокирующая ФС — на `ioDispatcher` vault и `dispatcher` движка (демон: `Dispatchers.IO`). Память на действие — блок 64 КиБ (шифр/хэш) и 1 МиБ (блок resumable), кроме скачивания из Drive (§10).

**UX.** Новый пункт прогресса «Сверяю с Google Drive: <путь>» (`SyncItemAction.Verify`) — текст в `SyncTexts`, экраны не меняются. Исчерпанный rate limit показывается как `Offline` («waiting for network») с сообщением о лимите в `lastError`; тысячи одинаковых проблем больше не появляются. Визуальной проверки экранов не требуется (новых экранов и состояний нет, только строка).

## 12. Тесты

- `SyncPlanBuilderTest`: план не вызывает удалённых чтений (вычислитель хэша только локальный); таблица §3 построчно.
- `SyncEngineIoBudgetTest` над `CountingRemoteStorage(InMemoryRemoteStorage)`: N = 50; прогон без изменений — 0 `read`/`write`/`stat`, 1 `listAll`; K локальных правок — K `write`, 0 `read`; K удалённых — K `read`; потерян индекс, файлы совпадают — N `read`, 0 `write`, 0 локальных записей; конфликт — 2 `read`, 1 `write`; число `save` индекса ≤ ⌈A/100⌉ + 1.
- `SyncEngineParallelTest` (виртуальное время): не более `parallelism` одновременных `write`/`read`; ни одно удаление не начинается до завершения переносов; `DeleteLocal` по-прежнему делает `stat` и не удаляет при отстающем списке; фатальная ошибка одного воркера отменяет остальных и даёт `Offline`; итоговый индекс полон.
- Существующие тесты движка (прогресс, удаления, скрытые пути, интеграция с шифрованием) — зелёные с `parallelism = 1` и по умолчанию.
- `Sha256`/`HashingSource`: совпадение инкрементального и разового дайджеста на границах блоков.
- `DriveHttpClientTest` (`MockEngine`, `runTest`): `Retry-After` в секундах и HTTP-дате; 403 `userRateLimitExceeded` повторяется, 403 `insufficientPermissions` — нет; пауза в границах `[d/2, d]` с фиксированным `Random`; исчерпание → `RemoteUnavailableException`.
- `DriveUploaderTest`: файл больше порога уходит блоками с `/*`, последний с итоговым размером; файл меньше порога — один multipart.
- `DrivePathResolverTest`: два параллельных `resolveOrCreateFolderPath("a/b")` — один запрос создания на сегмент.
- `FileSystemLocalVaultTest` (jvm): операции выполняются на переданном диспетчере.
- `DaemonConfigTest`: `MIMIR_SYNC_PARALLELISM` разбирается и проверяется на диапазон.
