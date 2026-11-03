---
directory: ui
source:
  kind: git
  blob_sha: 657bcbe46083a186567a973a467613cbae94b442
  commit_sha: e87f1148fd8185cdd0ff7269ac85792979a95a32
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
