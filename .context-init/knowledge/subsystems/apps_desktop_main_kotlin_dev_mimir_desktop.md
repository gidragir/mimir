---
directory: apps/desktop/src/main/kotlin/dev/mimir/desktop
source:
  kind: git
  blob_sha: be4b28c7c2f3ca55ea5cf3b08366dbe573459466
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
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

# Подсистема: Desktop Main Kotlin Dev Mimir Desktop (L1)

Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop` (15 файлов)

> **Идентификатор:** `apps_desktop_main_kotlin_dev_mimir_desktop`
> **Теги:** Kotlin (Gradle), apps_desktop_main_kotlin_dev_mimir_desktop

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/DesktopObsidianVaultDiscovery.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/FallbackFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/MacFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ProcessExecutor.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/WindowsFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/ZenityFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/platform/WindowsProtocolRegistrar.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/DesktopUpdateSupport.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/WindowsMsiApplier.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/window/PlatformWindowStyler.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class DesktopBackend(...) : AutoCloseable
enum class Mode
override fun close() = onClose()
object DaemonServiceFactory
suspend fun create( config: DaemonConfig, platform: TargetPlatform = detectTargetPlatform(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty()), updateTempDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "mimir-updates"), ): DesktopBackend
override suspend fun quiesce() = runtimeRef.get()?.quiesceForUpdate() ?: false
override suspend fun resume()
override suspend fun shutdown()
internal fun createUpdateApplier(...): UpdateApplier = DesktopApplierFactory.create( platform = platform, httpClient = httpClient, tempDir = updateTempDir, host = host, browserOpener = SystemBrowserOpener()::openUri, )
fun main(args: Array<String>)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
