---
directory: libs/ui/src/commonMain/kotlin/dev/mimir/ui
source:
  kind: git
  blob_sha: 3b80a8acb433e1eb288b7f8a2e285e5e9ca464f0
  commit_sha: 570bdf8b131c69da889f78491ee6adaf106b3095
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

# Подсистема: Ui CommonMain Kotlin Dev Mimir Ui (L1)

Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов)

> **Идентификатор:** `libs_ui_commonmain_kotlin_dev_mimir_ui`
> **Теги:** Kotlin (Gradle), libs_ui_commonmain_kotlin_dev_mimir_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/Destination.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/ObsidianStyle.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirComponents.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/components/MimirIcons.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/DaemonServiceUiModel.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/EngineMimirUiModel.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/FakeMimirUiModel.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/MimirUiModel.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/UpdateTexts.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/UpdateUiMapping.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/UpdateUiState.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/picker/PlatformFolderPicker.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
enum class Destination
companion object
fun fromString(value: String?): Destination = when (value?.lowercase())
fun MimirApp( model: MimirUiModel, touchInput: Boolean = false, initialDestination: Destination = Destination.Status, )
fun MimirApp( engine: SyncEngine, touchInput: Boolean = false, initialDestination: Destination = Destination.Status, ) = MimirApp(remember(engine)
fun MimirAppPreview()
fun MimirTheme(content: @Composable () -> Unit)
object MimirTokens
object Dark
object Light
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
