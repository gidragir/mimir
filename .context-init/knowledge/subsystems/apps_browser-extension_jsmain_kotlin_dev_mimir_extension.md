---
directory: apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension
source:
  kind: git
  blob_sha: 2a76fe41e43856b6234bfb22d457b608f5468a7a
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 10
  sampled_entries: 10
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Browser Extension JsMain Kotlin Dev Mimir Extension (L1)

Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension` (10 файлов)

> **Идентификатор:** `apps_browser-extension_jsmain_kotlin_dev_mimir_extension`
> **Теги:** Kotlin (Gradle), apps_browser-extension_jsmain_kotlin_dev_mimir_extension

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/MarkdownBlockRenderer.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/MarkdownInlineRenderer.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ChromeExtensionPlatform.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/update/ExtensionUpdateController.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/update/WebExtensionNotifyApplier.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/ExtensionVaultCache.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun main()
fun seedAccount(account: Account)
override fun observeSessionKey(key: String, onChanged: (String?) -> Unit)
override suspend fun getSessionString(key: String): String? = session[key]
override suspend fun setSessionString(key: String, value: String)
override suspend fun removeSessionKey(key: String)
override suspend fun clearSession() = session.clear()
override suspend fun getLocalString(key: String): String? = local[key]
override suspend fun setLocalString(key: String, value: String)
override suspend fun removeLocalKey(key: String)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
