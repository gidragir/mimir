---
directory: apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension
source:
  kind: git
  blob_sha: fda01497e10c504b5ddd0b9040cc193c455ce2a9
  commit_sha: 1a5aa0855a928bbbb5552e63d2859864a61e21ee
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 8
  sampled_entries: 8
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Browser Extension JsMain Kotlin Dev Mimir Extension (L1)

Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension` (8 файлов)

> **Идентификатор:** `apps_browser-extension_jsmain_kotlin_dev_mimir_extension`
> **Теги:** Kotlin (Gradle), apps_browser-extension_jsmain_kotlin_dev_mimir_extension

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ChromeExtensionPlatform.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/FakeExtensionPlatform.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/update/ExtensionUpdateController.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/update/WebExtensionNotifyApplier.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun main()
object SafeMarkdownRenderer
fun render( document: Document, markdown: String, onWikilinkClick: (String) -> Unit =
fun stripMarkdownToPlainText(markdown: String): String
fun flush()
fun wrapped(tag: String, inner: String): HTMLElement
class ChromeExtensionPlatform : ExtensionPlatform
override fun observeSessionKey(key: String, onChanged: (String?) -> Unit)
override suspend fun getSessionString(key: String): String? = readString(session, key)
override suspend fun setSessionString(key: String, value: String) = writeString(session, key, value)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
