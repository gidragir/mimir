---
directory: apps/android
source:
  kind: git
  blob_sha: d072b085b0e18bff5655dec89062ae6f6cf43776
  commit_sha: 799cec16d9d3f57d366553b65e11bd0d24956012
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

Подсистема `apps/android` с ключевыми точками входа: apps/android/build.gradle.kts

> **Идентификатор:** `apps_android`
> **Теги:** Kotlin (Gradle), apps_android

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/android/build.gradle.kts` | ⭐ Точка входа |
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
override suspend fun authorize(): SystemLoginResult
fun rememberActivitySystemAccountLogin( activity: ComponentActivity, accountPrefs: SharedPreferences, ): ActivitySystemAccountLogin
object AndroidRuntimeFactory
fun defaultHttpClient(): HttpClient = HttpClient(CIO)
fun create( context: Context, httpClient: HttpClient = defaultHttpClient(), scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), ): MimirRuntime
override suspend fun quiesce(): Boolean = runtimeRef?.quiesceForUpdate() ?: false
override suspend fun resume()
class MainActivity : ComponentActivity()
override fun onCreate(savedInstanceState: Bundle?)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
