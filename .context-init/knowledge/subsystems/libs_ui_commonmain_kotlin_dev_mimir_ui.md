---
directory: libs/ui/src/commonMain/kotlin/dev/mimir/ui
source:
  kind: git
  blob_sha: f83464375fa7876d715453685d033d4e72641eb0
  commit_sha: eb1dfb29262521892d6c4ce3c3bda7207dfd6e87
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
