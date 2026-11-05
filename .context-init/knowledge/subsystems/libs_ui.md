---
directory: libs/ui
source:
  kind: git
  blob_sha: 7ee4ab6c44fa36e94f05468b5ae949a6e21a379a
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 14
  sampled_entries: 14
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Libs Ui (L1)

Модуль и компоненты директории `libs/ui` (14 файлов)

> **Идентификатор:** `libs_ui`
> **Теги:** Kotlin (Gradle), libs_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/ui/build.gradle.kts` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/AccessStepTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/GoogleStepSystemAccountTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MimirThemeTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MobileAdaptationTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/StorageAccessBannerTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/SyncTextsTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardScreenshotTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardTest.kt` | Исходный код |
| `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class AccessStepTest
class ConflictsScreenTest
class GoogleStepSystemAccountTest
class MimirComponentsTest
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
