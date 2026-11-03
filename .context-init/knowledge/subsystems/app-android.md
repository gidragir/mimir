---
directory: app-android
source:
  kind: git
  blob_sha: 13c513140e2813d75bb0400e1a72d53b6373efae
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

# Подсистема: App Android (L1)

Подсистема `app-android` с ключевыми точками входа: app-android/package.json

> **Идентификатор:** `app-android`
> **Теги:** Kotlin (Gradle), app-android

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `app-android/build.gradle.kts` | Исходный код |
| `app-android/package.json` | ⭐ Точка входа |
| `app-android/src/main/kotlin/dev/mimir/android/ActivitySystemAccountLogin.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/AndroidRuntimeFactory.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/MainActivity.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/MimirApplication.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/auth/GisTokenSource.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/auth/PlayServicesGisAuthorizer.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/notify/NotificationContent.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/notify/StatusNotifier.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/platform/AndroidFolderPicker.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/platform/AndroidNetworkState.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/platform/AndroidStorageAccess.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/platform/TreeUriPaths.kt` | Исходный код |
| `app-android/src/main/kotlin/dev/mimir/android/work/SyncWorkScheduler.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class ActivitySystemAccountLogin( private val context: Context, private val accountPrefs: SharedPreferences, private val launchIntentSender: (suspend (IntentSenderRequest) -> ActivityResult)? = null, registry: ActivityResultRegistry? = null, ) : SystemAccountLogin
class MainActivity : ComponentActivity()
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
