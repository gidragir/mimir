---
directory: apps/daemon
source:
  kind: git
  blob_sha: 71c004e293bfdba4b7b762138ac227c14d20c410
  commit_sha: 799cec16d9d3f57d366553b65e11bd0d24956012
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 9
  sampled_entries: 9
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Daemon (L1)

Подсистема `apps/daemon` с ключевыми точками входа: apps/daemon/build.gradle.kts

> **Идентификатор:** `apps_daemon`
> **Теги:** Kotlin (Gradle), apps_daemon

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/daemon/build.gradle.kts` | ⭐ Точка входа |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DockerLogNotifyApplier.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/GoogleClients.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/Main.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/ProgressDto.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/UpdateRoutes.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
data class DaemonConfig( val bind: String = "127.0.0.1", val port: Int = BuildConfig.API_DEFAULT_PORT, val vaultPath: String? = null, val token: String? = null, val password: String? = null, val passwordFile: String? = null, val googleDesktopClientId: String? = null, val googl...
fun resolvePassword(): String?
fun isPasswordFileInsecure(): Boolean
fun save(configDir: String? = null)
override fun toString(): String = "DaemonConfig(bind=$bind, port=$port, vaultPath=$vaultPath, token=$
fun load(configDir: String? = null, env: Map<String, String> = System.getenv()): DaemonConfig
fun readFile(configDir: String): DaemonConfig
fun DaemonConfig.Companion.fromEnv(env: Map<String, String>): DaemonConfig = DaemonConfig().applyEnv(env)
class DaemonSettingsStore(initial: DaemonConfig) : RuntimeSettingsStore
override fun load() = RuntimeConfig( dataDir = Paths.get(configDir), vaultPath = config.vaultPath, syncIntervalSeconds = config.syncIntervalSeconds, syncParallelism = config.syncParallelism, rootFolder = config.rootFolder, )
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
