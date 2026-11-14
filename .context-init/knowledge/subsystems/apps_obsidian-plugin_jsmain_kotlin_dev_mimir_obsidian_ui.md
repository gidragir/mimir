---
directory: apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui
source:
  kind: git
  blob_sha: 58e94646435a68fe4d405a76c74c6d647ab65813
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Obsidian Plugin JsMain Kotlin Dev Mimir Obsidian Ui (L1)

Модуль и компоненты директории `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui` (6 файлов)

> **Идентификатор:** `apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian_ui`
> **Теги:** Kotlin (Gradle), apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/ConflictsModal.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/DeviceCodeModal.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/DomExt.kt` | Исходный код |
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
class MimirSettingTab(...) : PluginSettingTab(app, plugin)
override fun display()
data class PluginTexts( val locale: String, val statusNotConnected: String, val statusOffline: String, val statusYieldsToDaemon: String, val statusPausedForDaemon: String, val statusNeedsSignIn: String, val statusError: String, val statusUpToDate: String, val abortedNothingDel...
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
