---
directory: apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui
source:
  kind: git
  blob_sha: 88eaedb720fc231be060d832103ca989d046c268
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 8
  sampled_entries: 8
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Browser Extension JsMain Kotlin Dev Mimir Extension Ui (L1)

Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui` (8 файлов)

> **Идентификатор:** `apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui`
> **Теги:** Kotlin (Gradle), apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/AuthScreens.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/DomKit.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionUiState.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/FakeExtensionAuthHandler.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/GoogleExtensionAuthHandler.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt` | Исходный код |
| `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/UpdateBannerView.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class AuthScreens( private val dom: DomKit, private val controller: ExtensionController, private val platform: ExtensionPlatform, private val scope: CoroutineScope, )
fun loading(message: String, details: String? = null): HTMLElement = dom.el("section", "screen center").also
fun authRequired(state: ExtensionUiState.AuthRequired): HTMLElement
fun unlockRequired(state: ExtensionUiState.UnlockRequired): HTMLElement
internal class DomKit(val document: Document)
fun el(tag: String, className: String? = null, text: String? = null, testId: String? = null): HTMLElement
fun button(label: String, className: String = "btn", testId: String? = null, title: String? = null, onClick: () -> Unit): HTMLButtonElement
fun input(type: String, placeholder: String? = null, testId: String? = null): HTMLInputElement
interface ExtensionAuthHandler
suspend fun currentAccount(): Account?
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
