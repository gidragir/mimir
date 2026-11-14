# Оптимизация криптографии, локальной ФС, индекса синхронизации и тестовых фейков (:libs:core) — Implementation Plan

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: `superpowers:using-git-worktrees` → `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans` → `superpowers:verification-before-completion`. Шаги отмечаются чекбоксами (`- [ ]`). Прогресс фиксировать в `.superpowers/sdd/2026-11-14-wt2-core-crypto-and-sync-io/progress.md`.

**Goal:** Устранить >550 000 аллокаций в криптопримитивах (`Scrypt`, `Salsa20`, `XSalsa20`, `Poly1305`, `SecretBox`, `HashingSource`), сократить дисковый обход `FileSystemLocalVault` вдвое, перевести обновление `SyncIndex` на пакетный режим и инкрементальный `ChangeFeedStorage`, консолидировать `CanaryVerifier` и общие тестовые фейки в `:libs:core`.

**Architecture:** Все изменения строго локализованы внутри модуля `libs/core/**`. Внутренние циклы `Scrypt`/`Salsa20` переводятся на in-place буферы без изменения публичных сигнатур `RcloneCipher`. В `CanaryVerifier` добавляется `verifyOrInit` с обязательным пробросом `CancellationException` и закрытием потоков `.use {}`. В пакет `dev.mimir.core.testing` выносятся кэширующая фабрика `TestCiphers.forPassword` и единый `TestClock`.

**Tech Stack:** Kotlin Multiplatform 2.4.21 (`commonMain`, `jvmMain`, `jsMain`, `wasmJsMain`, `androidMain`), `kotlinx-coroutines`, `kotlinx-io`, `kotlinx-serialization`, `kotlinx-datetime`.

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md`, `docs/superpowers/specs/2026-10-24-mimir-sync-io-design.md`

**Worktree:** `.worktrees/wt-core-crypto-sync` (ветка `perf/core-crypto-and-sync`)

## Global Constraints

- `libs/core` не зависит от платформенных API (`java.*`, Android, DOM).
- В коде шифрования и валидации канареек при перехвате `Throwable` обязательно пробрасывать `CancellationException`.
- Все `RcloneVectors` (имена и файлы) обязаны проходить побитово без изменений.
- При добавлении новых опциональных полей в `@Serializable` классы индекса использовать `@EncodeDefault(EncodeDefault.Mode.NEVER)`.
- Все терминальные команды выполняются строго с префиксом `rtk`; навигация по коду — через `rtk context-init pack` и `rtk rg`.

## Инварианты Mimir

1. **Платформы:**
   - **JVM (Linux / Windows / macOS) и Android:** однопроходный обход `FileSystemLocalVault` уменьшает число системных вызовов `stat`/`lstat`/`readdir` вдвое; zero-allocation `Scrypt` и `XSalsa20` снижают нагрузку на GC.
   - **JS и WasmJs (Obsidian, Chromium MV3, Web-UI):** отсутствие 557 056 аллокаций `IntArray(16)` в `Scrypt.derive` и 3 075 аллокаций на каждый 64 КиБ блок в `XSalsa20` ускоряет разблокировку и потоковое шифрование в однопоточном JS-рантайме.
2. **Дизайн:**
   - Декоратор `EncryptedRemoteStorage` реализует `ChangeFeedStorage`, прозрачно расшифровывая пути изменений `RemoteChange`.
   - Эталонные тестовые двойники (`TestClock`, `TestCiphers`, `FakeSyncEngine`, `CountingLocalVault`, `CountingSyncStateStore`) живут в `dev.mimir.core.testing`.
3. **IO:**
   - На старте синхронизации `FileSystemLocalVault` выполняет **1 обход** дерева вместо 2 (`list()` обновляет кэш скрытых путей за символическими ссылками).
   - В фазе `metadata` `SyncIndex` обновляется одним пакетным копированием `Map` и **1 записью** на диск вместо $\lfloor M/100 \rfloor$ полных перезаписей.
   - При наличии `remoteChangeToken` в `SyncIndex` и поддержке `ChangeFeedStorage` `DefaultSyncEngine` запрашивает дельту `getChanges(token)` вместо полного `listAll()`.
4. **UX:**
   - Список `upcoming` в `SyncProgress` рассчитывается монотонно от `AtomicInt` курсора очереди и не «прыгает» назад при завершении более медленного параллельного воркера.

## Review Focus

1. **Побитовая совместимость с `rclone crypt`:** `RcloneCipherTest` и `RcloneVectors` на всех 3 таргетах (`jvmTest`, `jsTest`, `wasmJsNodeTest`) обязаны остаться зелёными после перехода `Scrypt`/`Salsa20`/`Poly1305`/`SecretBox` на in-place массивы.
2. **Отмена корутины во время чтения `.mimir_canary`:** `CanaryVerifier.verifyReadOnly` и `verifyOrInit` при выбросе `CancellationException` из `remote.read` обязаны пробросить его наружу, а не вернуть `CanaryVerifyResult.Invalid("Неверный мастер-пароль")`.
3. **Закрытие `Source` при неверном пароле:** если `DecryptingSource.readHeader` бросает `CryptException`, нижележащий `encrypted: Source` обязан быть закрыт (`close()`).
4. **Символьные ссылки в `FileSystemLocalVault`:** вызов `hiddenPaths()` после `list()` (и самостоятельно без предварительного `list()`) обязан возвращать все пути символических ссылок, чтобы файлы за ними не удалялись в облаке.
5. **Сбой прогона при инкрементальном `ChangeFeedStorage`:** если синхронизация прервалась ошибкой или отменой, `remoteChangeToken` в `SyncIndex` не должен продвигаться вперёд до успешного завершения прогона.

## Структура файлов

```
libs/core/src/commonMain/kotlin/dev/mimir/core/
  crypto/
    Salsa20.kt                   (изм.: coreInPlace, streamBlockInto без аллокаций)
    Scrypt.kt                    (изм.: переиспользование t и work в roMix/blockMix)
    XSalsa20.kt                  (изм.: заполнение массива через streamBlockInto)
    Poly1305.kt                  (изм.: mac(data, offset, length, key))
    SecretBox.kt                 (изм.: без copyOfRange на 64 КиБ)
    RcloneCipher.kt              (изм.: hexToByteArray, закрытие encrypted при ошибке заголовка)
    HashingSource.kt             (изм.: переиспользуемые copyBuffer и scratch)
    Sha256.kt                    (изм.: toHexString())
    CanaryVerifier.kt            (изм.: проброс CancellationException, .use {}, verifyOrInit, CANARY_BODY)
    EncryptedRemoteStorage.kt    (изм.: реализация ChangeFeedStorage)
  fs/
    FileSystemLocalVault.kt      (изм.: однопроходный walk с кэшем hiddenPaths, быстрый hasSymbolicLink)
  sync/
    SyncIndex.kt                 (изм.: withChanges, remoteChangeToken)
    SyncIndexJson.kt             (изм.: прямая сериализация FileSyncRecord, remoteChangeToken)
    IndexUpdater.kt              (изм.: updateAll для батча метаданных)
    DefaultSyncEngine.kt         (изм.: AtomicInt курсор, updateAll, инкрементальный ChangeFeedStorage)
    SyncPlanBuilder.kt           (изм.: O(depth) проверка isHidden по сегментам)
    ConflictResolver.kt          (изм.: удаление мёртвых Conflict(ByteArray)/Resolution/resolve)
    SyncEngine.kt                (изм.: удаление пустых start()/stop())
  api/
    AuthProvider.kt              (изм.: удаление неиспользуемого interface AuthProvider)
  testing/
    TestClock.kt                 (созд.: единый TestClock с advanceBy)
    TestCiphers.kt               (созд./пер.: кэширующая фабрика RcloneCipher по паролю и соли)
    FakeSyncEngine.kt            (созд.: общий фейк SyncEngine)
    CountingLocalVault.kt        (созд.: декоратор подсчёта вызовов LocalVault)
    CountingSyncStateStore.kt    (созд.: декоратор подсчёта сохранений SyncStateStore)
```

---

### Task 1: Zero-allocation криптопримитивы (`Salsa20`, `Scrypt`, `XSalsa20`, `Poly1305`, `SecretBox`, `RcloneCipher`, `HashingSource`, `Sha256`)

**Files:**
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Salsa20.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Scrypt.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/XSalsa20.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Poly1305.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/SecretBox.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/HashingSource.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Sha256.kt`
- Test: `libs/core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherTest.kt`

**Interfaces:**
- Produces:
  - `internal fun Salsa20.coreInPlace(state: IntArray, work: IntArray, rounds: Int = 20)`
  - `internal fun Salsa20.streamBlockInto(key: ByteArray, nonce8: ByteArray, counter: Long, state: IntArray, work: IntArray, out: ByteArray, outOffset: Int)`
  - `internal fun Poly1305.mac(data: ByteArray, key: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray`

- [ ] **Step 1: Написать тест закрытия нижележащего `Source` при ошибке расшифровки заголовка в `RcloneCipherTest.kt`**

Добавить тест `decrypt closes upstream source when header is invalid`, проверяющий, что `RawSource.close()` вызывается при выбросе `CryptException` из `cipher.decrypt(upstream).readByteArray()`.
Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.RcloneCipherTest"`
Expected: FAIL (`upstreamClosed` равен `false`).

- [ ] **Step 2: Перевести `Salsa20`, `Scrypt`, `XSalsa20`, `Poly1305`, `SecretBox`, `HashingSource`, `Sha256`, `RcloneCipher` на работу без лишних аллокаций и закрывать `encrypted` при сбое заголовка**

1. В `Salsa20.kt`: добавить `coreInPlace(state, work, rounds)` и `streamBlockInto(...)`.
2. В `Scrypt.kt`: в `roMix` выделить `val t = IntArray(16)` и `val work = IntArray(16)` один раз и передавать их в `blockMix(b, r, t, work)`.
3. В `XSalsa20.kt`: в `keystream` выделить одну пару `state = IntArray(16)`, `work = IntArray(16)` и заполнять `out` напрямую через `Salsa20.streamBlockInto` (хвост < 64 байт — через один временный `ByteArray(64)`).
4. В `Poly1305.kt`: добавить параметры `offset: Int = 0, length: Int = data.size` в `mac(...)`.
5. В `SecretBox.kt`: вызывать `Poly1305.mac(out, polyKey, offset = TAG_SIZE, length = out.size - TAG_SIZE)` в `seal` и `Poly1305.mac(box, polyKey, offset = TAG_SIZE, length = bodyLen)` в `open` без `copyOfRange`.
6. В `RcloneCipher.kt`:
   - В `DecryptingSource.readHeader` проверять `MAGIC` циклом `(0 until MAGIC.size).all { header[it] == MAGIC[it] }` и в `try/catch` при ошибке заголовка вызывать `encrypted.close()`.
   - В `fromKeyHex` заменить ручной `substring` на `hex.hexToByteArray()`.
7. В `HashingSource.kt`: хранить `private val copyBuffer = Buffer()` и `private val scratch = ByteArray(8192)`, переиспользуя их в `readAtMostTo`.
8. В `Sha256.kt:104`: заменить `joinToString` в `ByteArray.toHex()` на `toHexString()`.

- [ ] **Step 3: Прогнать тесты криптографии на JVM, JS и WasmJs**

Run: `rtk ./gradlew :libs:core:jvmTest :libs:core:wasmJsNodeTest --tests "dev.mimir.core.*"`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/ libs/core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherTest.kt
rtk oco -y
```

---

### Task 2: Исправление `CanaryVerifier` и вынос `TestCiphers.forPassword` + `TestClock` в `dev.mimir.core.testing`

**Files:**
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt`
- Modify: `libs/core/src/commonTest/kotlin/dev/mimir/core/TestCiphers.kt` (и экспорт в `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/TestCiphers.kt`)
- Create: `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/TestClock.kt`
- Modify: `libs/core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherKeyExportAndCanaryTest.kt`
- Modify: `libs/core/src/commonTest/kotlin/dev/mimir/core/ConflictResolverTest.kt`
- Modify: `libs/core/src/commonTest/kotlin/dev/mimir/core/SyncEngineProgressTest.kt`
- Modify: `libs/core/src/jvmTest/kotlin/dev/mimir/core/update/DailyUpdateCheckerTest.kt`

**Interfaces:**
- Produces:
  - `CanaryVerifier.CANARY_BODY: String`
  - `suspend fun CanaryVerifier.verifyOrInit(remote: RemoteStorage, cipher: Cipher, knownCanaryHash: String? = null): CanaryVerifyResult`
  - `object TestCiphers { suspend fun forPassword(password: String, salt: String? = null): RcloneCipher }` в `dev.mimir.core.testing`
  - `class TestClock(var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)) : Clock { fun advanceBy(duration: Duration) }` в `dev.mimir.core.testing`

- [ ] **Step 1: Написать падающие тесты на проброс `CancellationException` и `verifyOrInit` в `RcloneCipherKeyExportAndCanaryTest.kt`**

1. Тест `verifyReadOnly rethrows CancellationException`: `remote.read` бросает `CancellationException("cancelled")` → `assertFailsWith<CancellationException> { CanaryVerifier.verifyReadOnly(remote, cipher) }`.
2. Тест `verifyOrInit writes canary on empty vault and rejects wrong knownCanaryHash without network`.
Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.RcloneCipherKeyExportAndCanaryTest"`
Expected: FAIL.

- [ ] **Step 2: Реализовать исправления в `CanaryVerifier.kt`, создать `TestCiphers` и `TestClock` в `dev.mimir.core.testing`**

1. В `CanaryVerifier.kt`:
   - Добавить `const val CANARY_BODY = """{"magic":"$CANARY_MAGIC","version":1}"""`.
   - В `verifyReadOnly` обернуть `cipher.decrypt(remote.read(canaryPath)).use { it.readString() }` и добавить `catch (e: CancellationException) { throw e }` перед `catch (e: Throwable)`.
   - Добавить `suspend fun verifyOrInit(remote: RemoteStorage, cipher: Cipher, knownCanaryHash: String? = null): CanaryVerifyResult`.
2. Создать `TestCiphers` и `TestClock` в `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/` и перевести `RcloneCipherKeyExportAndCanaryTest.kt`, `ConflictResolverTest.kt`, `SyncEngineProgressTest.kt`, `DailyUpdateCheckerTest.kt` на их использование.

- [ ] **Step 3: Прогнать тесты**

Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.RcloneCipherKeyExportAndCanaryTest"`
Expected: PASS (время выполнения `RcloneCipherKeyExportAndCanaryTest` сокращается в ~4 раза за счёт кэша ключей).

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt libs/core/src/commonMain/kotlin/dev/mimir/core/testing/ libs/core/src/commonTest/ libs/core/src/jvmTest/
rtk oco -y
```

---

### Task 3: Однопроходное сканирование `FileSystemLocalVault` и оптимизация `hasSymbolicLink`

**Files:**
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt:30-140`
- Test: `libs/core/src/jvmTest/kotlin/dev/mimir/core/fs/FileSystemLocalVaultTest.kt`
- Modify: `libs/core/src/jvmTest/kotlin/dev/mimir/core/fs/VaultWatcherTest.kt:139-161`

**Interfaces:**
- Produces: кэширование `lastHiddenPaths: Set<String>` внутри `FileSystemLocalVault.list()`, возвращаемое методом `hiddenPaths()` без повторного обхода ФС (если `list()` ещё не вызывался — `hiddenPaths()` выполняет обход сам).

- [ ] **Step 1: Написать тест в `FileSystemLocalVaultTest.kt`, проверяющий корректность `list()` + `hiddenPaths()` с симлинками**

Проверить, что после `vault.list()` вызов `vault.hiddenPaths()` возвращает все символические ссылки в корне и во вложенных папках, а после добавления новой симлинки и повторного `vault.list()` набор обновляется.
Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.fs.FileSystemLocalVaultTest"`

- [ ] **Step 2: Объединить обход в `FileSystemLocalVault.kt` и перевести тест `PollingVaultWatcher` в `VaultWatcherTest.kt:139` на `runTest`**

1. В `FileSystemLocalVault`:
   - Внутри `list()` за один вызов `walk` собирать и `List<LocalEntry>`, и `Set<String>` для символических ссылок (`cachedHiddenPaths`).
   - В `entryOf(relativePath: String, file: Path, size: Long)` использовать уже созданный `child: Path` из `walk`, не вызывая `absolute(relativePath)` повторно.
   - В `hasSymbolicLink(relativePath)` идти по сегментам `var cur = rootPath; for (seg in relativePath.split('/')) { if (seg.isEmpty()) continue; cur = Path(cur, seg); if (isSymbolicLink(cur)) return true }`.
2. В `VaultWatcherTest.kt:139-161` заменить `runBlocking` на `runTest` с виртуальным временем.

- [ ] **Step 3: Прогнать тесты ФС и вотчера**

Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.fs.*"`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt libs/core/src/jvmTest/kotlin/dev/mimir/core/fs/
rtk oco -y
```

---

### Task 4: Пакетный `SyncIndex.withChanges`, `AtomicInt` очередь, инкрементальный `ChangeFeedStorage` и очистка мёртвого кода

**Files:**
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndex.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndexJson.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/IndexUpdater.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlanBuilder.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedRemoteStorage.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictResolver.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncEngine.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/StubSyncEngine.kt`
- Modify: `libs/core/src/commonMain/kotlin/dev/mimir/core/api/AuthProvider.kt`
- Create: `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/CountingLocalVault.kt`, `CountingSyncStateStore.kt`, `FakeSyncEngine.kt`
- Test: `libs/core/src/commonTest/kotlin/dev/mimir/core/IndexUpdaterTest.kt`, `SyncEngineIoBudgetTest.kt`

**Interfaces:**
- Produces:
  - `fun SyncIndex.withChanges(put: Iterable<FileSyncRecord>, remove: Iterable<String>, remoteChangeToken: String? = this.remoteChangeToken): SyncIndex`
  - `suspend fun IndexUpdater.updateAll(changes: List<SyncAction>)`
  - `EncryptedRemoteStorage : RemoteStorage, ChangeFeedStorage`

- [ ] **Step 1: Написать падающие тесты в `IndexUpdaterTest.kt` и `SyncEngineIoBudgetTest.kt`**

1. В `IndexUpdaterTest`: пакет из 250 `UpdateMetadata` при вызове `updateAll` сохраняет состояние на диск ровно **1 раз** (а не 2–3 раза).
2. В `SyncEngineIoBudgetTest`: при повторном `syncOnce()` над хранилищем, реализующим `ChangeFeedStorage`, с сохранённым `remoteChangeToken` в `SyncIndex` и 1 изменённым файлом в `getChanges(token)`, движок вызывает `getChanges` и **не вызывает `listAll()`** (`listAllCalls == 0`).
Run: `rtk ./gradlew :libs:core:jvmTest --tests "dev.mimir.core.IndexUpdaterTest" --tests "dev.mimir.core.SyncEngineIoBudgetTest"`
Expected: FAIL.

- [ ] **Step 2: Реализовать `withChanges`, `updateAll`, `AtomicInt` в `DefaultSyncEngine`, `ChangeFeedStorage` в `EncryptedRemoteStorage` и удалить мёртвый код**

1. В `SyncIndex.kt` и `SyncIndexJson.kt`:
   - Добавить `val remoteChangeToken: String? = null` в `SyncIndex` и `IndexFileDto` (с `@EncodeDefault(EncodeDefault.Mode.NEVER)`).
   - Сделать `FileSyncRecord` `@Serializable` и добавить `withChanges(...)` через `records.toMutableMap()`.
2. В `IndexUpdater.kt`: добавить `suspend fun updateAll(actions: List<SyncAction>)` и вызывать его в `DefaultSyncEngine.kt:166`.
3. В `DefaultSyncEngine.kt`:
   - Заменить `Channel<Int>(Channel.UNLIMITED)` в `runPhase` на `AtomicInt(0)`.
   - Если `remoteStorage is ChangeFeedStorage`: на первом прогоне получать `getLatestChangeToken()` и сохранять в `SyncIndex`; на следующих прогонах при непустом `index.current.records` и `remoteChangeToken != null` запрашивать `getChanges(token)` и обновлять снэпшот удалённых записей дельтой (с сохранением защиты `SyncAbortedException`, если дельта опустошила непустой индекс).
4. В `EncryptedRemoteStorage.kt`: реализовать `ChangeFeedStorage`, делегируя `inner as? ChangeFeedStorage` и расшифровывая сегменты пути в `RemoteChange`.
5. В `SyncPlanBuilder.kt:151`: проверять `isHidden(path, hidden)` поиском `'/'` в `path` без перебора всего `hidden`.
6. Удалить неиспользуемые `Conflict(ByteArray, ByteArray)`/`Resolution`/`resolve` из `ConflictResolver.kt`, `interface AuthProvider` из `AuthProvider.kt`, `start()`/`stop()` из `SyncEngine.kt` и `StubSyncEngine.kt`.
7. Вынести `CountingLocalVault`, `CountingSyncStateStore` и `FakeSyncEngine` в `dev.mimir.core.testing`.

- [ ] **Step 3: Прогнать все тесты `:libs:core`**

Run: `rtk ./gradlew :libs:core:allTests && rtk context-init check --compact`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/core/
rtk oco -y
```
