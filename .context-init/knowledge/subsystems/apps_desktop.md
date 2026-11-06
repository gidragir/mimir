---
directory: apps/desktop
source:
  kind: git
  blob_sha: 2c13ade69527df049e1644c65675ccfbd2b7ddfc
  commit_sha: f257ad8e5833023549c25d73ac019e725362bc88
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

# Подсистема: Desktop (L1)

Подсистема `apps/desktop` с ключевыми точками входа: apps/desktop/build.gradle.kts

> **Идентификатор:** `apps_desktop`
> **Теги:** Kotlin (Gradle), apps_desktop

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/build.gradle.kts` | ⭐ Точка входа |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscovery.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/FallbackFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/MacFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/WindowsFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/platform/WindowsProtocolRegistrar.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusInterfaces.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/DesktopUpdateSupport.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/WindowsMsiApplier.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun macBundleVersion(version: String): String
class DesktopBackend(...) : AutoCloseable
enum class Mode
override fun close() = onClose()
object DaemonServiceFactory
suspend fun create( config: DaemonConfig, platform: TargetPlatform = detectTargetPlatform(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty()), updateTempDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "mimir-updates"), ): DesktopBackend
override suspend fun quiesce() = runtimeRef.get()?.quiesceForUpdate() ?: false
override suspend fun resume()
override suspend fun shutdown()
fun main(args: Array<String>)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
