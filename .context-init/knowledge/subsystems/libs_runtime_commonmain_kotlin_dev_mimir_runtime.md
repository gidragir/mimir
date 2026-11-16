---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime
source:
  kind: git
  blob_sha: 6769186afb1410b0107926413dafdb62f4615a65
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
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

# Подсистема: Runtime CommonMain Kotlin Dev Mimir Runtime (L1)

Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime` (15 файлов)

> **Идентификатор:** `libs_runtime_commonmain_kotlin_dev_mimir_runtime`
> **Теги:** Kotlin (Gradle), libs_runtime_commonmain_kotlin_dev_mimir_runtime

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/AccountStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/Canary.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/CloudStorageFactory.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/EngineAssembler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/FileTokenStorage.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonRuntimeSettingsStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/SyncScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/LoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/RuntimeAuthCoordinator.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class AccountStore(dataDir: Path)
fun email(): String? = read()?.email
suspend fun method(): DaemonLoginMethod?
fun save(email: String?, method: DaemonLoginMethod)
fun delete()
typealias InvalidPasswordException = dev.mimir.client.InvalidPasswordException
typealias SyncNotConfiguredException = dev.mimir.client.SyncNotConfiguredException
suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path)
fun interface CloudStorageFactory
fun create(httpClient: HttpClient, tokenSource: AccessTokenSource, config: RuntimeConfig): ChangeFeedStorage?
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
