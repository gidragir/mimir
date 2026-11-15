---
directory: apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui
source:
  kind: git
  blob_sha: 0aba25d9b82200206dafb68b1f66871a0c5af019
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 7
  sampled_entries: 7
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Obsidian Plugin JsMain Kotlin Dev Mimir Obsidian Ui (L1)

Модуль и компоненты директории `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui` (7 файлов)

> **Идентификатор:** `apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian_ui`
> **Теги:** Kotlin (Gradle), apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/ConflictsModal.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/DeviceCodeModal.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/DomExt.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/MassDeletionModal.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/MimirSettingTab.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/PluginTexts.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/StatusView.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class ConflictsModal( app: App, private val conflicts: List<String>, private val texts: PluginTexts, ) : Modal(app)
override fun onOpen()
override fun onClose()
class DeviceCodeModal( app: App, private val signIn: DeviceSignIn, private val texts: PluginTexts, private val onComplete: (Boolean) -> Unit =
fun HTMLElement.empty()
fun HTMLElement.createEl(tag: String, o: dynamic = null): HTMLElement
fun HTMLElement.createDiv(o: dynamic = null): HTMLElement
class MassDeletionModal( app: App, private val count: Int, private val texts: PluginTexts, private val onDecision: (DeletionDecision) -> Unit, ) : Modal(app)
class MimirSettingTab(...) : PluginSettingTab(app, plugin)
override fun display()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
