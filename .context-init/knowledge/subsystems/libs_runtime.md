---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime
source:
  kind: git
  blob_sha: 132ea75d447636ffe18c43f6c9387febea825eaf
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 15
  sampled_entries: 15
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Libs Runtime (L1)

Модуль и компоненты директории `libs/runtime` (15 файлов)

> **Идентификатор:** `libs_runtime`
> **Теги:** Kotlin (Gradle), libs_runtime

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/Canary.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/EngineAssembler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/SyncScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/ApkInstallApplier.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClient.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/RuntimeUpdates.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateFacade.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/VerifiedFileDownloader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class InvalidPasswordException(...) : IllegalArgumentException(message) /** * Проверяет пароль по файлу-канарейке в корне [remote] (имя открытое, содержимое шифруется [cipher]). * [remote] — хранилищ...
class SyncNotConfiguredException(message: String) : IllegalStateException(message)
class EngineAssembler( private val httpClient: HttpClient, private val cloudFactory: CloudStorageFactory, private val watcherFactory: VaultWatcherFactory, private val ioDispatcher: CoroutineDispatcher, private val scope: CoroutineScope, private val config: () -> RuntimeConfig, )
class JsonSyncStateStore( val indexFile: Path, /** Где исполняется блокирующая запись и чтение индекса; JVM/Android передают `Dispatchers.IO`. */ private val ioDispatcher: CoroutineDispatcher, ) : SyncStateStore
class MimirRuntime( private val settings: RuntimeSettingsStore, private val logins: LoginRegistry, watcherFactory: VaultWatcherFactory, private val passwordProvider: suspend () -> String?, ioDispatcher: CoroutineDispatcher, private val httpClient: HttpClient, cloudFactory: Clo...
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
