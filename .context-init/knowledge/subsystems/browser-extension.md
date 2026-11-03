---
directory: browser-extension
source:
  kind: git
  blob_sha: 817544c3cec86eff698970454d31b1618fc7f292
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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

# Подсистема: Browser Extension (L1)

Подсистема `browser-extension` с ключевыми точками входа: browser-extension/package.json

> **Идентификатор:** `browser-extension`
> **Теги:** Kotlin (Gradle), browser-extension

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `browser-extension/build.gradle.kts` | Исходный код |
| `browser-extension/package.json` | ⭐ Точка входа |
| `browser-extension/scripts/capture-screenshots.mjs` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ChromeExtensionPlatform.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/AuthScreens.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/GoogleExtensionAuthHandler.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/update/ExtensionUpdateController.kt` | Исходный код |
| `browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt` | Исходный код |
| `browser-extension/src/jsMain/resources/popup.css` | Исходный код |
| `browser-extension/src/jsMain/resources/tokens.css` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
