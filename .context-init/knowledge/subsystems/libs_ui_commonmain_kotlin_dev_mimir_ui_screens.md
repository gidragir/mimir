---
directory: libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens
source:
  kind: git
  blob_sha: c987eed93fdd016aaff22ca352a5c947c2d85a0c
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 11
  sampled_entries: 11
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Ui CommonMain Kotlin Dev Mimir Ui Screens (L1)

Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (11 файлов)

> **Идентификатор:** `libs_ui_commonmain_kotlin_dev_mimir_ui_screens`
> **Теги:** Kotlin (Gradle), libs_ui_commonmain_kotlin_dev_mimir_ui_screens

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/AboutUpdateCard.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/AndroidBanners.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsScreen.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/ConflictsTexts.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/StartupScreen.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusSyncCards.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/SyncTexts.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverScreen.kt` | Исходный код |
| `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverSections.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun AboutUpdateCard( state: UpdateUiState, syncRunning: Boolean, onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, onOpenReleasePage: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, currentVersion: String = BuildConfig.VERSION, )
fun StorageAccessBanner( storageAccess: StorageAccess, modifier: Modifier = Modifier, )
fun SignInRequiredBanner( onSignIn: () -> Unit, modifier: Modifier = Modifier, )
fun StatusBanners( model: MimirUiModel, onOpenSetup: () -> Unit, modifier: Modifier = Modifier, )
fun ConflictsScreen(model: MimirUiModel, onBack: () -> Unit)
fun resolve(entry: UiConflictEntry, keepConflict: Boolean)
fun ConflictsScreenPreview()
object ConflictsTexts
fun SetupWizardScreen(model: MimirUiModel, onComplete: () -> Unit, pollIntervalMillis: Long = 5_000)
fun SetupWizardScreenPreview()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
