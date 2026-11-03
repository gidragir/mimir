---
directory: ui
source:
  kind: git
  blob_sha: 41d715c8705aa86e2ed2ce37e6ce92363fecfd92
  commit_sha: 59b2f01957ad853d3e73656dd4fdd3f849fcb7f6
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

# Подсистема: Ui (L1)

Подсистема `ui` с ключевыми точками входа: ui/package.json

> **Идентификатор:** `ui`
> **Теги:** Kotlin (Gradle), ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `ui/build.gradle.kts` | Исходный код |
| `ui/package.json` | ⭐ Точка входа |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/AccessStepTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/GoogleStepSystemAccountTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/MimirComponentsTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/MimirThemeTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/MobileAdaptationTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/StorageAccessBannerTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/SyncTextsTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardScreenshotTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardTest.kt` | Исходный код |
| `ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class AccessStepTest
class ConflictsScreenTest
class GoogleStepSystemAccountTest
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
