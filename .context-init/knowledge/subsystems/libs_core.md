---
directory: libs/core
source:
  kind: git
  blob_sha: 6f7736da9ef7979d583cef854619a5132bd1d78b
  commit_sha: bca5a71aa25e84ea197c3bf1e2540a4de155de19
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

# Подсистема: Core (L1)

Подсистема `libs/core` с ключевыми точками входа: libs/core/build.gradle.kts

> **Идентификатор:** `libs_core`
> **Теги:** Kotlin (Gradle), libs_core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/build.gradle.kts` | ⭐ Точка входа |
| `libs/core/scripts/gen-rclone-vectors.sh` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/crypto/AndroidKeystoreSecretStore.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/crypto/RandomNonce.android.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/AndroidFileObserverVaultWatcher.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/LastModified.android.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/SymbolicLink.android.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/crypto/RandomNonce.js.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/fs/LastModified.js.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/update/ConsoleUpdateLogger.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/update/FetchUpdateHttpClient.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/crypto/RandomNonce.jvm.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/fs/NioWatchServiceVaultWatcher.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/fs/SymbolicLink.jvm.kt` | Исходный код |
| `libs/core/src/wasmJsMain/kotlin/dev/mimir/core/crypto/RandomNonce.wasmJs.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class AndroidKeystoreCipher(private val alias: String) : SecretCipher
override fun encrypt(plain: ByteArray): ByteArray
override fun decrypt(encrypted: ByteArray): ByteArray
override fun get(key: String): String? = prefs.getString(key, null)
override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
override fun remove(key: String) = prefs.edit().remove(key).apply()
fun AndroidKeystoreSecretStore( context: Context, prefsName: String = "[REDACTED]"
internal actual fun randomNonce(): ByteArray = ByteArray(24).also(secureRandom::nextBytes)
class AndroidFileObserverVaultWatcher( private val root: File, rules: SyncRules = SyncRules(), private val debounce: Duration = 1.seconds, echoTtl: Duration = 2.seconds, private val clock: Clock = Clock.System, ) : VaultWatcher
data class FsEvent(val dir: File, val event: Int, val path: String?) : WatcherEvent
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
