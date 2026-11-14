---
directory: libs/ui
source:
  kind: git
  blob_sha: f5dda16a6c63955262da856c76a0519b10d777eb
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 4
  sampled_entries: 4
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Ui (L1)

Подсистема `libs/ui` с ключевыми точками входа: libs/ui/build.gradle.kts

> **Идентификатор:** `libs_ui`
> **Теги:** Kotlin (Gradle), libs_ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/ui/build.gradle.kts` | ⭐ Точка входа |
| `libs/ui/src/androidMain/kotlin/dev/mimir/ui/platform/ClipboardEntries.android.kt` | Исходный код |
| `libs/ui/src/desktopMain/kotlin/dev/mimir/ui/platform/ClipboardEntries.desktop.kt` | Исходный код |
| `libs/ui/src/wasmJsMain/kotlin/dev/mimir/ui/platform/ClipboardEntries.wasmJs.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
