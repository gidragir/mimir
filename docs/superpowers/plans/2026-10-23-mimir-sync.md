# Mimir Sync Engine & Conflict Prevention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать переносимый двусторонний движок синхронизации (`SyncEngine`) и систему разрешения конфликтов (`ConflictResolver`) с нулевой потерей данных в модуле `core`, полностью протестированные на JVM, JS и Wasm.

**Architecture:** Чистый Kotlin в `core/commonMain`:
- Абстракция и модель метаданных: `SyncIndex`, `FileSyncRecord`, `SyncStateStore`.
- Расширенная абстракция `LocalVault` с потоковой передачей (`Source`/`Sink`), реактивными изменениями и тестовым `InMemoryLocalVault`.
- Сопоставление путей `GlobMatcher` для фильтрации служебных файлов Obsidian (`SyncRules`).
- Трехсторонняя сверка изменений: `SyncPlanBuilder` строит детерминированный план действий (`SyncPlan`).
- Движок `DefaultSyncEngine`, выполняющий операции над `LocalVault` и `EncryptedRemoteStorage(inner = RemoteStorage, cipher = RcloneCipher)`.
- Разрешение конфликтов `DefaultConflictResolver`, сохраняющее обе копии с меткой `(conflicted copy <timestamp>)`.

**Tech Stack:** Kotlin 2.4.21 KMP (jvm, js, wasmJs, android), kotlinx-io (`Source`, `Sink`, `Buffer`), kotlinx-coroutines (`Flow`, `StateFlow`, `Mutex`), kotlinx-datetime (`Clock`, `Instant`).

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-sync-design.md` (родительская: `docs/superpowers/specs/2026-10-23-mimir-design.md`, §6.2, §6.5).

---

## Global Constraints

- Все файлы создаются в `core/src/commonMain/kotlin/dev/mimir/core/` (подпакеты `sync`, `rules`, `testing`); тесты — в `core/src/commonTest/kotlin/dev/mimir/core/`.
- `core` остается чистым Kotlin без платформенных зависимостей и без внешних БД (SQLite запрещен).
- Команды запускаются строго через `mise x java@temurin-27 -- ./gradlew …` с префиксом `rtk`.
- TDD: сначала падающий тест, затем реализация.
- Именование коммитов: conventional commits (`feat(core): …`, `test(core): …`).
- Версию в `gradle.properties` вручную не менять; изменения сопровождаются `pnpm changeset`.

---

## Review Focus

1. Потоковое чтение и запись файлов без вычитывания всего хранилища в память.
2. Детерминированность построения плана синхронизации для всех 12 состояний матрицы сверки.
3. Нулевая потеря данных при конфликтах (обе версии файла сохранены и загружены в облако).
4. Устойчивость к сбоям: прерывание на середине плана не повреждает `SyncIndex` и не вызывает дублирования.
5. Корректное игнорирование путей `.obsidian/workspace*.json` и кэшей на обеих сторонах.

---

### Task 1: Glob Matcher и фильтрация SyncRules

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/rules/GlobMatcher.kt`
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/rules/SyncRules.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/GlobMatcherTest.kt`

**Interfaces:**
- Produces: `internal object GlobMatcher { fun matches(pattern: String, path: String): Boolean }`
- Extends: `SyncRules.isExcluded(path: String): Boolean`

- [ ] **Step 1: Write failing tests** `GlobMatcherTest`: проверка паттернов `*`, `**`, `?`, точных совпадений, отрицаний, исключений `.obsidian/workspace*.json`, `.obsidian/cache/**`, `.trash/**`, системных `**/.DS_Store`.
- [ ] **Step 2: Run** `mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*GlobMatcherTest*'` → FAIL.
- [ ] **Step 3: Implement** `GlobMatcher` (чистый алгоритм сопоставления путей без регулярных выражений платформы) и метод `SyncRules.isExcluded(path: String)`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*GlobMatcherTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): glob matcher and path exclusion rules`.

---

### Task 2: Модель состояния синхронизации (SyncIndex и SyncStateStore)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndex.kt`, `SyncStateStore.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncIndexTest.kt`

**Interfaces:**
- Produces:
  - `data class FileSyncRecord(val relativePath: String, val localId: VaultFileId, val baseHash: String, val localSize: Long, val localModifiedAt: Instant, val remotePath: RemotePath, val remoteRevision: String?, val remoteModifiedAt: Instant, val remoteSize: Long)`
  - `data class SyncIndex(val version: Int = 1, val records: Map<String, FileSyncRecord> = emptyMap())`
  - `interface SyncStateStore { suspend fun load(): SyncIndex; suspend fun save(index: SyncIndex) }`
  - `class InMemorySyncStateStore(initial: SyncIndex = SyncIndex()) : SyncStateStore`

- [ ] **Step 1: Write failing tests** `SyncIndexTest`: добавление, обновление, удаление записей, поиск по относительному пути, сериализация/десериализация состояния `InMemorySyncStateStore`.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*SyncIndexTest*'` → FAIL.
- [ ] **Step 3: Implement** структуры данных `FileSyncRecord`, `SyncIndex` и интерфейс `SyncStateStore` с реализацией `InMemorySyncStateStore`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*SyncIndexTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): sync index model and state store`.

---

### Task 3: Расширение LocalVault и реализация InMemoryLocalVault

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/api/LocalVault.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryLocalVault.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/InMemoryLocalVaultTest.kt`

**Interfaces:**
- Updates `LocalVault`:
  - `suspend fun getEntry(relativePath: String): LocalEntry?`
  - `suspend fun write(relativePath: String, data: Source): LocalEntry`
  - `suspend fun deleteByPath(relativePath: String)`
- Produces:
  - `class InMemoryLocalVault : LocalVault` с поддержкой потоковых операций, реактивного `MutableSharedFlow<VaultChange>`, задержек и симуляции сбоев ввода-вывода (`throwOnRead`, `throwOnWrite`).

- [ ] **Step 1: Write failing tests** `InMemoryLocalVaultTest`: проверка `write` по относительному пути, получение `getEntry`, чтение через `Source`, удаление, генерация событий `VaultChange` в `changes()`, эмуляция сбоев I/O.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*InMemoryLocalVaultTest*'` → FAIL.
- [ ] **Step 3: Implement** методы в `LocalVault` и класс `InMemoryLocalVault`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*InMemoryLocalVaultTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): expand local vault api and implement in-memory vault`.

---

### Task 4: Разрешение конфликтов (ConflictResolver)

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictResolver.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/ConflictResolverTest.kt`

**Interfaces:**
- Produces:
  - `data class ConflictDetails(val path: String, val localEntry: LocalEntry, val remoteEntry: RemoteEntry, val baseRecord: FileSyncRecord?)`
  - `data class ConflictOutcome(val mainPath: String, val conflictPath: String, val conflictContent: Source)`
  - `class DefaultConflictResolver(val clock: Clock = Clock.System) : ConflictResolver`
  - Хелпер формирования имени: `ConflictResolver.generateConflictPath(path: String, timestamp: Instant): String`

- [ ] **Step 1: Write failing tests** `ConflictResolverTest`: форматирование имени `<name> (conflicted copy <timestamp>).<ext>`, обработка файлов без расширения, обработка вложенных путей `a/b/c.md`, гарантия сохранения обеих копий контента.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*ConflictResolverTest*'` → FAIL.
- [ ] **Step 3: Implement** `DefaultConflictResolver` и утилиты генерации безопасных имен файлов.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*ConflictResolverTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): zero data loss conflict resolver`.

---

### Task 5: Построитель плана трехсторонней сверки (SyncPlanBuilder)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlan.kt`, `SyncPlanBuilder.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncPlanBuilderTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SyncAction` (`Upload`, `Download`, `Conflict`, `DeleteRemote`, `DeleteLocal`, `UpdateMetadata`, `NoOp`)
  - `data class SyncPlan(val actions: List<SyncAction>)`
  - `class SyncPlanBuilder(val rules: SyncRules = SyncRules()) { suspend fun buildPlan(local: List<LocalEntry>, remote: List<RemoteEntry>, index: SyncIndex, hashCalculator: suspend (path: String, isLocal: Boolean) -> String): SyncPlan }`

- [ ] **Step 1: Write failing tests** `SyncPlanBuilderTest`: исчерпывающее тестирование матрицы 12 состояний (новый локальный/удаленный, изменения с одной стороны, обеих сторон с равными/различными хешами, удаления, удаление + модификация, фильтрация служебных файлов).
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*SyncPlanBuilderTest*'` → FAIL.
- [ ] **Step 3: Implement** `SyncPlan`, `SyncAction` и логику трехсторонней сверки в `SyncPlanBuilder`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*SyncPlanBuilderTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): 3-way reconciliation sync plan builder`.

---

### Task 6: Реализация DefaultSyncEngine

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncEngine.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt`
- Remove: `core/src/commonMain/kotlin/dev/mimir/core/sync/StubSyncEngine.kt` (замена на боевой движок)
- Test: `core/src/commonTest/kotlin/dev/mimir/core/DefaultSyncEngineTest.kt`

**Interfaces:**
- Produces:
  - `class DefaultSyncEngine(val localVault: LocalVault, val remoteStorage: RemoteStorage, val stateStore: SyncStateStore, val conflictResolver: ConflictResolver = DefaultConflictResolver(), val rules: SyncRules = SyncRules(), val clock: Clock = Clock.System) : SyncEngine`

- [ ] **Step 1: Write failing tests** `DefaultSyncEngineTest`:
  - Базовая двусторонняя синхронизация (загрузка и выгрузка).
  - Смена статусов `status` (`Idle` -> `Syncing` -> `Idle`).
  - Coalescing параллельных вызовов `syncNow()`.
  - Пошаговое сохранение состояния в `SyncStateStore`.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*DefaultSyncEngineTest*'` → FAIL.
- [ ] **Step 3: Implement** пайплайн выполнения плана синхронизации в `DefaultSyncEngine`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*DefaultSyncEngineTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(core): default sync engine implementation`.

---

### Task 7: Сквозная интеграция, шифрование и симуляция сбоев

**Files:**
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncEngineIntegrationTest.kt`

- [ ] **Step 1: Write integration tests**:
  - Связка `DefaultSyncEngine` + `InMemoryLocalVault` + `EncryptedRemoteStorage(InMemoryRemoteStorage, RcloneCipher)`.
  - Проверка сквозного шифрования: в `InMemoryRemoteStorage` пути и содержимое зашифрованы по стандарту rclone, в `LocalVault` — открытый текст.
  - Тест разрешения конфликта: генерация `(conflicted copy)`, обе копии зашифрованы и доступны в хранилище.
  - Тест прерванной синхронизации (failure halfway): вызов сбоя на 3-м файле из 5, последующий `syncNow()` успешно завершает передачу оставшихся 2 файлов без повторного перезаписывания первых 3.
  - Тест гонки изменений: файл изменен в `LocalVault` прямо во время фазы выполнения плана -> проверка отсутствия перезаписи устаревшим состоянием.
- [ ] **Step 2: Run** `mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*SyncEngineIntegrationTest*'` → PASS.
- [ ] **Step 3: Commit** `test(core): end-to-end sync engine and failure recovery tests`.

---

### Task 8: Мультиплатформенная верификация и релизный changeset

- [ ] **Step 1: Run full test suite across targets**:
  `mise x java@temurin-27 -- ./gradlew :core:jvmTest`  
  `CHROME_BIN=/usr/bin/chromium mise x java@temurin-27 -- ./gradlew :core:jsTest`  
  `mise x java@temurin-27 -- ./gradlew :core:wasmJsTest`  
  `mise run test`
- [ ] **Step 2: Generate Changeset**:
  Зафиксировать этап изменений через `pnpm changeset` с кратким описанием добавления движка синхронизации и разрешения конфликтов.
- [ ] **Step 3: Final Commit**:
  `chore(sync): changeset for sync engine and conflict prevention`.
