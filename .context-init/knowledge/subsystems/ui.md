---
directory: ui
source:
  kind: git
  blob_sha: 9ef094fbcb593a56ea3db3cfb8847b61fcf63bb2
  commit_sha: 0015ea5942cd9570dd8e5f4a879cead20db73253
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
