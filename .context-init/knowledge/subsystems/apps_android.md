---
directory: apps/android
source:
  kind: git
  blob_sha: db7e8b9688324ad0b36e5f5df66bfa29001c66ef
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
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

# Подсистема: Android (L1)

Модуль и компоненты директории `apps/android` (15 файлов)

> **Идентификатор:** `apps_android`
> **Теги:** Kotlin (Gradle), apps_android

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/android/build.gradle.kts` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/ActivitySystemAccountLogin.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/AndroidRuntimeFactory.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/MainActivity.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/MimirApplication.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/auth/GisTokenSource.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/auth/PlayServicesGisAuthorizer.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/notify/NotificationContent.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/notify/StatusNotifier.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/platform/AndroidFolderPicker.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/platform/AndroidNetworkState.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/platform/AndroidStorageAccess.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/platform/TreeUriPaths.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/work/MimirWorkerFactory.kt` | Исходный код |
| `apps/android/src/main/kotlin/dev/mimir/android/work/SyncWorkScheduler.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class ActivitySystemAccountLogin( private val context: Context, private val accountPrefs: SharedPreferences, private val launchIntentSender: (suspend (IntentSenderRequest) -> ActivityResult)? = null, registry: ActivityResultRegistry? = null, ) : SystemAccountLogin
class MainActivity : ComponentActivity()
class MimirAppLifecycleObserver( private val runtime: () -> MimirRuntime, private val workScheduler: () -> SyncWorkScheduler, private val scope: CoroutineScope, ) : DefaultLifecycleObserver
class MimirApplication : Application(), Configuration.Provider
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
