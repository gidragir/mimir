---
directory: runtime
source:
  kind: git
  blob_sha: d58ed5388bafc8ac8995d0fd37bc4a40a9516673
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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

# Подсистема: Runtime (L1)

Подсистема `runtime` с ключевыми точками входа: runtime/package.json

> **Идентификатор:** `runtime`
> **Теги:** Kotlin (Gradle), runtime

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `runtime/package.json` | ⭐ Точка входа |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/EngineAssembler.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/SyncScheduler.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginProvider.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/ApkInstallApplier.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClient.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/RuntimeUpdates.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateFacade.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateScheduler.kt` | Исходный код |
| `runtime/src/commonMain/kotlin/dev/mimir/runtime/update/VerifiedFileDownloader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class EngineAssembler( private val httpClient: HttpClient, private val cloudFactory: CloudStorageFactory, private val watcherFactory: VaultWatcherFactory, private val ioDispatcher: CoroutineDispatcher, private val scope: CoroutineScope, private val config: () -> RuntimeConfig, )
class JsonSyncStateStore( val indexFile: Path, /** Где исполняется блокирующая запись и чтение индекса; JVM/Android передают `Dispatchers.IO`. */ private val ioDispatcher: CoroutineDispatcher, ) : SyncStateStore
class MimirRuntime( private val settings: RuntimeSettingsStore, private val logins: LoginRegistry, watcherFactory: VaultWatcherFactory, private val passwordProvider: suspend () -> String?, ioDispatcher: CoroutineDispatcher, private val httpClient: HttpClient, cloudFactory: Clo...
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
