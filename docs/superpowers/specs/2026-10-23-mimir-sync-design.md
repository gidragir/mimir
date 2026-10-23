# Mimir — этап 2: движок синхронизации и разрешение конфликтов (Sync Engine & Conflict Prevention)

Дата: 2026-10-23  
Статус: на согласование  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md` (§6.2, §6.2a, §6.5). При конфликте приоритет у родительской спеки.  
Спека этапа 1: `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md`.

---

## 1. Контекст и архитектурные границы

Этап 1 (rclone-совместимое шифрование `RcloneCipher`, `EncryptedRemoteStorage`) полностью завершён и протестирован на JVM, JS и Wasm.

Этап 2 реализует ядро синхронизации Mimir внутри `core`:
- Работает поверх портов `LocalVault` и `RemoteStorage` (декорированного `EncryptedRemoteStorage(inner, cipher)`).
- Не зависит от Google Drive, OAuth и конкретных платформ (работает в `commonMain` на jvm, android, js, wasmJs).
- Не использует внешних БД (SQLite запрещен для переносимого KMP ядра). Индекс синхронизации сериализуется и персистится через абстракцию `SyncStateStore`.
- Все тесты запускаются в `commonTest` с использованием `InMemoryRemoteStorage`, `InMemoryLocalVault` и боевого `RcloneCipher`.

---

## 2. Цели и критерии успеха

1. **Модель и хранилище состояния (`SyncIndex`, `SyncStateStore`)**:
   - Отслеживание локальных и удалённых метаданных каждого файла: `relativePath`, `VaultFileId`, mtime, размер, криптографический хеш содержимого (SHA-256), удаленная ревизия (`revision`), baseline-хеш последней успешной синхронизации (`baseHash`).
   - Полноценная поддержка трехсторонней сверки (3-way reconciliation).
2. **Абстракция `LocalVault`**:
   - Потоковое чтение и запись через `kotlinx.io.Source` / `kotlinx.io.Sink`.
   - Поддержка операций как по абстрактному `VaultFileId`, так и по нормализованному относительному пути `relativePath`.
   - Реактивный поток изменений файловой системы `Flow<VaultChange>`.
   - Тестовая реализация `InMemoryLocalVault` с эмуляцией задержек, потока событий и инъекцией сбоев.
3. **Двусторонняя синхронизация (`SyncEngine`)**:
   - Построение детерминированного плана синхронизации (`SyncPlan`): `Upload`, `Download`, `Conflict`, `DeleteRemote`, `DeleteLocal`, `NoOp`, `UpdateMetadata`.
   - Фильтрация служебных файлов Obsidian по glob-паттернам (`SyncRules`: `.obsidian/workspace*.json`, `.obsidian/cache*`, `.trash/**`).
   - Устойчивость к прерываниям: атомарное обновление метаданных синхронизированных файлов; сбой в процессе не повреждает индекс и не приводит к потере данных при повторном запуске.
4. **Профилактика и разрешение конфликтов (`ConflictResolver`)**:
   - Принцип Zero Data Loss: обе версии сохраняются.
   - Автоматическое создание конфликтной копии: `<path>/<name> (conflicted copy <timestamp>).<ext>` с ISO-подобным безопасным форматом метки времени (`YYYY-MM-DD-HHmmss`).
   - Конфликтная копия немедленно загружается в удалённое хранилище, предотвращая рассинхронизацию между пирами.
5. **Тестирование**:
   - 100% покрытие в `commonTest` (JVM, JS, Wasm).
   - Симуляция сбоев сети, гонок изменений, модификации файлов в процессе синхронизации, древовидных переименований и исключений I/O.

---

## 3. Не-цели этапа

- Реализация фонового демона ОС, tray-иконок и фоновой службы Android (это задачи этапов 5–10).
- Реализация адаптера Google Drive и OAuth-клиента (это этап 3).
- Парсинг бинарных форматов файлов или слияние текстовых Markdown-диффов внутри заметок (в Mimir конфликт разрешается сохранением копий, слияние заметок — ответственность пользователя или внешних плагинов).
- Поддержка пустых директорий на облаках, не имеющих концепции папок (в Drive/rclone папки виртуальны и задаются путями файлов).

---

## 4. Архитектура и структуры данных

### 4.1 Модель метаданных и индекс синхронизации

```kotlin
package dev.mimir.core.sync

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import kotlin.time.Instant

/** Состояние файла на момент последней успешной синхронизации. */
data class FileSyncRecord(
    val relativePath: String,           // Нормализованный путь: "folder/note.md"
    val localId: VaultFileId,           // Идентификатор в LocalVault
    val baseHash: String,               // SHA-256 хеш содержимого (hex) при последней синхронизации
    val localSize: Long,                // Локальный размер
    val localModifiedAt: Instant,       // Локальный mtime
    val remotePath: RemotePath,         // Зашифрованный/виртуальный удаленный путь
    val remoteRevision: String?,        // Ревизия облака (ETag / generation / rev)
    val remoteModifiedAt: Instant,      // Удаленный mtime
    val remoteSize: Long,               // Удаленный размер
)

/** Индекс состояния синхронизации хранилища. */
data class SyncIndex(
    val version: Int = 1,
    val records: Map<String, FileSyncRecord> = emptyMap(), // relativePath -> Record
) {
    fun find(path: String): FileSyncRecord? = records[path]
    fun withRecord(record: FileSyncRecord): SyncIndex = copy(records = records + (record.relativePath to record))
    fun withoutRecord(path: String): SyncIndex = copy(records = records - path)
}

/** Хранилище индекса синхронизации. */
interface SyncStateStore {
    suspend fun load(): SyncIndex
    suspend fun save(index: SyncIndex)
}
```

### 4.2 Расширение абстракции `LocalVault`

Для поддержки двусторонней синхронизации абстракция `LocalVault` должна уметь создавать и находить файлы по относительному пути (актуально при выгрузке из облака):

```kotlin
package dev.mimir.core

import kotlinx.coroutines.flow.Flow
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.time.Instant

interface LocalVault {
    suspend fun list(): List<LocalEntry>
    suspend fun getEntry(relativePath: String): LocalEntry?
    suspend fun read(id: VaultFileId): Source
    suspend fun write(relativePath: String, data: Source): LocalEntry
    suspend fun delete(id: VaultFileId)
    suspend fun deleteByPath(relativePath: String)
    fun changes(): Flow<VaultChange>
}
```

### 4.3 Фильтрация файлов (`SyncFilter` и Glob Matcher)

Служебные файлы Obsidian и системные артефакты исключаются из синхронизации:
- Базовые паттерны по умолчанию:
  - `.obsidian/workspace*.json` (окна и вкладки сессии Obsidian)
  - `.obsidian/cache/**`
  - `.trash/**`
  - `.git/**`
  - `**/.DS_Store`
  - `**/desktop.ini`
  - `**/thumbs.db`
- `GlobMatcher`: чистая KMP реализация сопоставления путей (поддерживает `*`, `**`, `?`).

---

## 5. Алгоритм трехсторонней сверки (3-Way Reconciliation)

### 5.1 Построение плана (`SyncPlanBuilder`)

Сравнение происходит между:
1. `LocalEntry` (текущее локальное состояние).
2. `RemoteEntry` (текущее удаленное состояние через `EncryptedRemoteStorage`).
3. `FileSyncRecord` из `SyncIndex` (базовое состояние последней синхронизации).

**Матрица перехода состояний:**

| Локально vs База | Удалённо vs База | Локальный хеш vs Удалённый хеш | Действие (`SyncAction`) |
|---|---|---|---|
| Не изменился | Не изменился | — | `NoOp` |
| Изменился | Не изменился | — | `Upload` (отправка в облако) |
| Не изменился | Изменился | — | `Download` (загрузка в vault) |
| Изменился | Изменился | Совпадает | `UpdateMetadata` (содержимое одинаковое, обновить mtime/rev) |
| Изменился | Изменился | Различается | `Conflict` (генерация conflicted copy) |
| Удалён | Не изменился | — | `DeleteRemote` |
| Не изменился | Удалён | — | `DeleteLocal` |
| Удалён | Изменился | — | `ConflictDownload` (удаленный файл модифицирован другим клиентом -> восстановить локально) |
| Изменился | Удалён | — | `Upload` (локальный файл важнее устаревшего удаления -> перезалить) |
| Новый (нет в базе) | Отсутствует | — | `Upload` |
| Отсутствует | Новый (нет в базе) | — | `Download` |
| Новый (нет в базе) | Новый (нет в базе) | Совпадает | `UpdateMetadata` |
| Новый (нет в базе) | Новый (нет в базе) | Различается | `Conflict` |

### 5.2 Спецификация действий (`SyncAction`)

```kotlin
sealed interface SyncAction {
    val path: String

    data class Upload(
        override val path: String,
        val localEntry: LocalEntry,
        val remotePath: RemotePath,
    ) : SyncAction

    data class Download(
        override val path: String,
        val remoteEntry: RemoteEntry,
    ) : SyncAction

    data class DeleteRemote(
        override val path: String,
        val remotePath: RemotePath,
    ) : SyncAction

    data class DeleteLocal(
        override val path: String,
        val localId: VaultFileId,
    ) : SyncAction

    data class Conflict(
        override val path: String,
        val localEntry: LocalEntry,
        val remoteEntry: RemoteEntry,
        val baseRecord: FileSyncRecord?,
    ) : SyncAction

    data class UpdateMetadata(
        override val path: String,
        val record: FileSyncRecord,
    ) : SyncAction

    data class NoOp(override val path: String) : SyncAction
}

data class SyncPlan(
    val actions: List<SyncAction>,
) {
    val hasChanges: Boolean get() = actions.any { it !is SyncAction.NoOp && it !is SyncAction.UpdateMetadata }
}
```

---

## 6. Политика и механизм разрешения конфликтов (`ConflictResolver`)

### 6.1 Формат имени конфликтного файла

При возникновении конфликта для пути `folder/note.md`:
1. В основной файл `folder/note.md` сохраняется версия с удалённого хранилища (`remoteEntry`).
2. Локальная версия сохраняется как:
   `folder/note (conflicted copy YYYY-MM-DD-HHmmss).md`
3. Если расширение отсутствует: `folder/file (conflicted copy YYYY-MM-DD-HHmmss)`.
4. Временная метка формируется в UTC от `Clock.System.now()`.
5. Конфликтная копия немедленно записывается в `LocalVault` и помечается для синхронизации в облако (`Upload`).
6. В статус синхронизации передается предупреждение о разрешенном конфликте.

---

## 7. Жизненный цикл и безопасность выполнения (`SyncEngine`)

### 7.1 Порядок выполнения плана

1. Вычисление плана (`SyncPlanBuilder.buildPlan`).
2. Выполнение `Conflict` (создание конфликтных копий до перезаписи основных данных).
3. Выполнение `Download` (актуализация локального хранилища).
4. Выполнение `Upload` (отправка локальных изменений и созданных конфликтных копий).
5. Выполнение `DeleteRemote` и `DeleteLocal`.
6. Пошаговая фиксация `SyncStateStore.save(updatedIndex)` после каждого успешно обработанного файла для гарантии отказоустойчивости при аварийном завершении.

### 7.2 Защита от гонок и блокировки

- Вычисление хеша перед отправкой: если mtime или размер локального файла изменился между построением плана и чтением потока, операция для данного файла отменяется (переносится на следующий цикл синхронизации).
- Мьютекс синхронизации (`kotlinx.coroutines.sync.Mutex`): параллельные вызовы `syncNow()` coalescing-сворачиваются в один текущий запуск плюс не более одного последующего.

---

## 8. План тестирования (TDD, `commonTest`)

1. **`GlobMatcherTest`**: проверка масок `*`, `**`, `?`, путей `.obsidian/workspace*.json`, вложенных путей `.trash/sub/file.md`.
2. **`InMemoryLocalVaultTest`**: потоковое чтение, запись, получение записей, удаление, реактивный поток изменений.
3. **`SyncPlanBuilderTest`**: детальное тестирование всех 12 ветвей матрицы трехсторонней сверки.
4. **`ConflictResolverTest`**: проверка формирования имен конфликтных файлов, сохранение обеих копий, сохранение бинарных и текстовых данных без искажений.
5. **`SyncEngineIntegrationTest`**:
   - Полная начальная заливка (clean remote).
   - Полная начальная выкачка (clean local).
   - Инкрементальные изменения в обе стороны.
   - Сквозное разрешение конфликта с `EncryptedRemoteStorage` и реальным `RcloneCipher`.
   - Восстановление после прерывания (halfway failure injection).
   - Игнорирование служебных файлов Obsidian.
   - Переименование файла/директории.

---

## 9. Критерии готовности этапа

1. Все интерфейсы, структуры данных и реализации добавлены в `core` (`commonMain`).
2. Код полностью покрыт тестами в `core` (`commonTest`).
3. `mise run test` проходит на JVM, JS и WasmJs.
4. Отсутствуют секреты, внешние платформенные зависимости и нарушения SQLite-инварианта.
5. Изменения зафиксированы в changesets.
