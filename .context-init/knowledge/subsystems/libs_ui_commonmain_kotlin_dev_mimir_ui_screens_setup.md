---
directory: libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup
source:
  kind: git
  blob_sha: 469fd4a8224cf7406c6d3d8078ad2327851f6605
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Ui CommonMain Kotlin Dev Mimir Ui Screens Setup (L1)

Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup` (6 файлов)

> **Идентификатор:** `libs_ui_commonmain_kotlin_dev_mimir_ui_screens_setup`
> **Теги:** Kotlin (Gradle), libs_ui_commonmain_kotlin_dev_mimir_ui_screens_setup

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/AccessStep.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/DeviceCodeStep.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/GoogleStep.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/InitialResolutionStep.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/PasswordStep.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/VaultStep.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun AccessStep(onNext: () -> Unit)
internal fun DeviceCodeStep( model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit, onBack: (() -> Unit)?, )
fun GoogleStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit)
fun InitialResolutionStep(model: MimirUiModel, onComplete: () -> Unit)
suspend fun finish()
fun PasswordStep(model: MimirUiModel, onNext: () -> Unit)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
