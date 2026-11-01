# Mimir Browser Extension (`:browser-extension`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать автономное расширение Chromium Manifest V3 (`:browser-extension`) для быстрого чтения зашифрованных заметок Obsidian из Google Drive в боковой панели (`chrome.sidePanel`) и полноэкранной вкладке без скачивания хранилища на ПК.

**Architecture:** Расширение переиспользует KMP-модули `:core` (`RcloneCipher`, `EncryptedRemoteStorage`, `SyncRules`) и `:drive` (`GoogleDriveStorage`, `DrivePathResolver`, `GoogleAuthClient`, `RefreshingTokenSource`). Единая точка входа `popup.html` + `popup.js` управляется однонаправленным автоматом состояний `ExtensionController` (`StateFlow<ExtensionUiState>`) поверх порта `ExtensionPlatform`: в узкой `chrome.sidePanel` работает пошаговая навигация Master-Detail (Список ↔ Заметка с кнопкой «← Назад»), а во вкладке (`popup.html?mode=tab`) — двухколоночная раскладка. Производный 80-байтный ключ `RcloneCipher` хранится исключительно в RAM (`chrome.storage.session`), а в `chrome.storage.local` кэшируются только зашифрованные `RemoteEntry` и `.canary_hash`.

**Tech Stack:** Kotlin Multiplatform 2.4.21 (`js` browser target), `kotlinx.coroutines`, `kotlinx.serialization`, `ktor-client-js`, `kotlinx-io`, Chromium WebExtensions API Manifest V3 (`chrome.sidePanel`, `chrome.storage.session`, `chrome.storage.local`, `chrome.identity`).

**Spec:** `docs/superpowers/specs/2026-11-01-mimir-browser-extension-design.md`

## Инварианты Mimir

1. **Платформы:**
   - **Chromium (Linux, Windows, macOS — Chrome, Brave, Edge, Vivaldi, Opera):** основная цель. Клик по иконке расширения открывает `chrome.sidePanel` (узкий вид, пошаговый переход Список → Заметка), кнопка `↗` открывает полноэкранную вкладку `popup.html?mode=tab&path=...` (двухпанельный вид). В браузерах без `chrome.sidePanel` автоматически открывается стандартный Popup (`action.default_popup = "popup.html"`).
   - **Android / Desktop JVM / Docker-демон / Плагин Obsidian:** изменения в `:core` (`RcloneCipher.exportKeyBytes`/`fromKeyBytes`, `CanaryVerifier`) обратно совместимы и компилируются на всех KMP-таргетах (`jvm`, `android`, `js`, `wasmJs`).
2. **Дизайн:**
   - *Порт и адаптер + Декоратор:* `EncryptedRemoteStorage(GoogleDriveStorage(...), RcloneCipher)` из `:core` и `:drive`.
   - *Порт платформенных побочных эффектов:* интерфейс `ExtensionPlatform` (`browser-extension`) с боевой реализацией `ChromeExtensionPlatform` и тестовым двойником `FakeExtensionPlatform`.
   - *Однонаправленный поток данных (UDF):* `ExtensionController` публикует `StateFlow<ExtensionUiState>`, `PopupDomView` рендерит состояние в DOM и пробрасывает пользовательские события обратно в контроллер.
3. **IO:**
   - На $N$ файлов в хранилище первичная загрузка делает $\lceil N / 1000 \rceil$ постраничных вызовов `files.list` без чтения тел файлов; повторное открытие Side Panel или вкладки делает **0 сетевых запросов**, читая зашифрованные пути из `chrome.storage.local` и расшифровывая их в RAM сессионным ключом из `chrome.storage.session`.
   - Чтение заметки делает ровно **1 запрос** `files.get?alt=media` по клику пользователя с потоковой блочной расшифровкой по 64 КиБ.
4. **UX:**
   - Токены палитры Obsidian (`#1e1e1e`, `#262626`, `#363636`, `#705dcf`, `#dcddde`, `#8e8e8e`) и адаптивная светлая тема.
   - Полный набор состояний (`Initializing`, `AuthRequired`, `UnlockRequired`, `VaultLoading`, `VaultReady` с поддержкой `Loading` / `Empty` / `Error` / `Offline` / `Content`).

## Global Constraints

- Все долгоживущие идентификаторы живут только в `gradle.properties` (`mimir.appName`, `mimir.version`, `mimir.extensionPublicKey`) и пробрасываются в `manifest.json` и код при сборке.
- Сборка и тесты (`mise run check`, `./gradlew :browser-extension:check`) обязаны проходить без единого секрета в окружении и без Android SDK.
- Строгий CSP Manifest V3 (`script-src 'self'; object-src 'self'`): запрещены inline-скрипты в `popup.html`, `eval` и рендеринг пользовательского Markdown через `innerHTML`.
- Мастер-пароль, 80-байтный ключ `RcloneCipher`, открытые имена заметок и их расшифрованный текст **никогда** не записываются в `chrome.storage.local`.
- Все команды терминала запускаются через префикс `rtk`.

## Review Focus

- Файлы в корне или подпапках Google Drive со случайными незашифрованными именами (например, загруженные пользователем вручную мимо Mimir): `VaultTreeBuilder` обязан перехватывать `CryptException` на отдельных файлах и молча пропускать их, не роняя построение дерева всего хранилища.
- Повторная проверка неверного пароля при наличии сохранённого `canaryHash` в `chrome.storage.local`: `CanaryVerifier` и `ExtensionController` обязаны отклонять неверный пароль локально за 0 сетевых обращений к Google Drive.
- Переход по внутренней ссылке `[[Короткое имя]]` без указания папки или расширения `.md` (а также `[[Папка/Заметка|Алиас]]`): `VaultTreeBuilder.resolveWikilink` обязан находить заметку как по точному относительному пути, так и по имени файла без `.md` (без учёта регистра).
- Попытка XSS через Markdown (`[click](javascript:alert(1))`, `[x](vbscript:...)`, `[x](data:text/html,...)` и сырые HTML-теги `<script>alert(1)</script>`): `SafeMarkdownRenderer` обязан рендерить сырой HTML как безопасный текстовый узел, а ссылки с небезопасными схемами превращать в обычный текст без `href`.
- Нажатие кнопки `🔒 Заблокировать` во время открытой заметки: `ExtensionController.lock()` обязан очищать `chrome.storage.session` (ключ шифрования и выбранную заметку), но сохранять `refresh_token` и зашифрованный кэш дерева в `chrome.storage.local`, переводя UI на экран `UnlockRequired` (а не `AuthRequired`).

---

### Task 1: Сессионный экспорт ключей `RcloneCipher` и мультиплатформенный `CanaryVerifier` в `:core`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherKeyExportAndCanaryTest.kt`

**Interfaces:**
- Consumes: `dev.mimir.core.Cipher`, `dev.mimir.core.RemoteStorage`, `dev.mimir.core.RemotePath`, `dev.mimir.core.Sha256`, `dev.mimir.core.toHex`
- Produces:
  - `RcloneCipher.exportKeyBytes(): ByteArray`
  - `RcloneCipher.exportKeyHex(): String`
  - `RcloneCipher.Companion.KEY_SIZE: Int = 80`
  - `RcloneCipher.Companion.fromKeyBytes(keys: ByteArray, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher`
  - `RcloneCipher.Companion.fromKeyHex(hex: String, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher`
  - `object CanaryVerifier`:
    - `const val CANARY_FILENAME = ".mimir_canary"`
    - `const val CANARY_MAGIC = "mimir-vault-canary"`
    - `fun computeCanaryHash(cipher: Cipher): String`
    - `suspend fun verifyReadOnly(remote: RemoteStorage, cipher: Cipher, knownCanaryHash: String? = null): CanaryVerifyResult`
  - `sealed interface CanaryVerifyResult`: `data class Valid(val canaryHash: String)`, `data class Invalid(val reason: String)`

- [ ] **Step 1: Write the failing test in `core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherKeyExportAndCanaryTest.kt`**

```kotlin
package dev.mimir.core

import dev.mimir.core.testing.InMemoryRemoteStorage
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcloneCipherKeyExportAndCanaryTest {
    @Test
    fun exportedKeyHexRestoresIdenticalCipherWithoutScrypt() = runTest {
        val original = RcloneCipher.create("correct-horse-battery-staple")
        val hex = original.exportKeyHex()
        assertEquals(160, hex.length)

        val restored = RcloneCipher.fromKeyHex(hex)
        val encryptedName = original.encryptName("Проекты")
        assertEquals("Проекты", restored.decryptName(encryptedName))

        val plainBuffer = Buffer().apply { writeString("# Заметка\nТекст") }
        val encryptedStream = original.encrypt(plainBuffer)
        assertEquals("# Заметка\nТекст", restored.decrypt(encryptedStream).readString())
    }

    @Test
    fun fromKeyBytesRejectsInvalidLength() {
        assertFailsWith<IllegalArgumentException> {
            RcloneCipher.fromKeyBytes(ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            RcloneCipher.fromKeyHex("deadbeef")
        }
    }

    @Test
    fun canaryVerifierRejectsWrongPasswordLocallyWhenKnownHashProvided() = runTest {
        val rightCipher = RcloneCipher.create("secret-1")
        val wrongCipher = RcloneCipher.create("secret-2")
        val knownHash = CanaryVerifier.computeCanaryHash(rightCipher)
        val remote = CountingRemoteStorage(InMemoryRemoteStorage())

        val result = CanaryVerifier.verifyReadOnly(remote, wrongCipher, knownCanaryHash = knownHash)
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(result)
        assertEquals(0, remote.statCalls + remote.readCalls + remote.listCalls)
    }

    @Test
    fun canaryVerifierValidatesRemoteCanaryFileAndRejectsWrongCipher() = runTest {
        val rightCipher = RcloneCipher.create("vault-pass")
        val wrongCipher = RcloneCipher.create("wrong-pass")
        val remote = InMemoryRemoteStorage()
        val body = Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), rightCipher.encrypt(body))

        val valid = CanaryVerifier.verifyReadOnly(remote, rightCipher)
        assertIs<CanaryVerifier.CanaryVerifyResult.Valid>(valid)
        assertEquals(CanaryVerifier.computeCanaryHash(rightCipher), valid.canaryHash)

        val invalid = CanaryVerifier.verifyReadOnly(remote, wrongCipher)
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(invalid)
    }

    @Test
    fun canaryVerifierHandlesLegacyVaultWithoutCanaryFileByTestingEntryNames() = runTest {
        val rightCipher = RcloneCipher.create("legacy-pass")
        val wrongCipher = RcloneCipher.create("wrong-pass")
        val remote = InMemoryRemoteStorage()
        val encName = rightCipher.encryptName("Заметка.md")
        remote.write(RemotePath(encName), rightCipher.encrypt(Buffer().apply { writeString("hello") }))

        assertIs<CanaryVerifier.CanaryVerifyResult.Valid>(CanaryVerifier.verifyReadOnly(remote, rightCipher))
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(CanaryVerifier.verifyReadOnly(remote, wrongCipher))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :core:jvmTest --tests "dev.mimir.core.RcloneCipherKeyExportAndCanaryTest"`
Expected: FAIL with unresolved references `exportKeyHex`, `fromKeyHex`, `CanaryVerifier`.

- [ ] **Step 3: Implement key export/import in `RcloneCipher.kt` and `CanaryVerifier` in `CanaryVerifier.kt`**

In `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`:
- Store `private val nameKey: ByteArray` in the primary constructor (`private val nameEme: Eme = Eme(nameKey)`).
- Add `fun exportKeyBytes(): ByteArray = dataKey + nameKey + nameTweak` and `fun exportKeyHex(): String = exportKeyBytes().toHex()`.
- In `RcloneCipher.Companion`: add `const val KEY_SIZE = 80`, `fun fromKeyBytes(keys: ByteArray, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher`, and `fun fromKeyHex(hex: String, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher` (validating `hex.length == KEY_SIZE * 2` and hex characters).

In `core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt`:
- Implement `object CanaryVerifier` with `CANARY_FILENAME = ".mimir_canary"`, `CANARY_MAGIC = "mimir-vault-canary"`, `private const val CANARY_HASH_PROBE = "mimir-canary-hash"`.
- `computeCanaryHash(cipher: Cipher): String = Sha256.digest(cipher.encryptName(CANARY_HASH_PROBE).encodeToByteArray()).toHex()`.
- `suspend fun verifyReadOnly(remote: RemoteStorage, cipher: Cipher, knownCanaryHash: String? = null): CanaryVerifyResult`:
  1. Compute `candidateHash = computeCanaryHash(cipher)`. If `knownCanaryHash != null`, return `Valid(candidateHash)` when `knownCanaryHash == candidateHash`, else `Invalid("Неверный мастер-пароль")`.
  2. Check `remote.stat(RemotePath(CANARY_FILENAME))`: if present, decrypt `remote.read(RemotePath(CANARY_FILENAME))` via `cipher.decrypt(...)`; if `CryptException` or decoded text does not contain `CANARY_MAGIC`, return `Invalid("Неверный мастер-пароль")`, else `Valid(candidateHash)`.
  3. If `.mimir_canary` is absent, call `remote.list(RemotePath(""))`. If empty, return `Valid(candidateHash)`. Otherwise check if at least one non-reserved entry's first path segment decrypts cleanly via `cipher.decryptName`; if none decrypts, return `Invalid("Неверный мастер-пароль")`, else `Valid(candidateHash)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :core:jvmTest :core:jsTest --tests "dev.mimir.core.RcloneCipherKeyExportAndCanaryTest"`
Expected: PASS on both JVM and JS targets.

- [ ] **Step 5: Commit**

```bash
rtk git add core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherKeyExportAndCanaryTest.kt
rtk oco
```

---

### Task 2: Контракт `ExtensionPlatform`, сериализуемые модели кэша и `VaultTreeBuilder` в `:browser-extension`

**Files:**
- Modify: `gradle.properties`
- Modify: `browser-extension/build.gradle.kts`
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt`
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt`
- Create: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/platform/FakeExtensionPlatform.kt`
- Test: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/vault/VaultTreeBuilderTest.kt`

**Interfaces:**
- Consumes: `dev.mimir.core.Cipher`, `dev.mimir.core.RemoteEntry`, `dev.mimir.core.RemotePath`, `dev.mimir.core.rules.SyncRules`, `dev.mimir.drive.auth.OAuthTokens`, `dev.mimir.drive.auth.TokenStorage`
- Produces:
  - `gradle.properties`: `mimir.extensionPublicKey`
  - `ExtensionBuildConfig`: `GOOGLE_DESKTOP_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_SECRET`, `GOOGLE_DEVICE_CLIENT_ID`, `GOOGLE_DEVICE_CLIENT_SECRET`
  - `interface ExtensionPlatform`:
    - `suspend fun getSessionString(key: String): String?`
    - `suspend fun setSessionString(key: String, value: String)`
    - `suspend fun removeSessionKey(key: String)`
    - `suspend fun clearSession()`
    - `suspend fun getLocalString(key: String): String?`
    - `suspend fun setLocalString(key: String, value: String)`
    - `suspend fun removeLocalKey(key: String)`
    - `suspend fun clearLocal()`
    - `fun getRedirectUrl(path: String = "oauth2"): String`
    - `suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean = true): String`
    - `fun openFullTab(relativePath: String?)`
    - `fun openExternalUrl(url: String)`
    - `suspend fun copyToClipboard(text: String)`
  - `class ExtensionTokenStorage(private val platform: ExtensionPlatform) : TokenStorage`
  - `sealed interface VaultTreeNode`:
    - `val name: String`, `val relativePath: String`
    - `data class Folder(override val name: String, override val relativePath: String, val children: List<VaultTreeNode>) : VaultTreeNode`
    - `data class File(override val name: String, override val relativePath: String, val encryptedPath: RemotePath, val size: Long, val modifiedEpochSec: Long) : VaultTreeNode`
  - `data class DecryptedVaultSnapshot(val tree: List<VaultTreeNode>, val flatNotes: List<VaultTreeNode.File>)`
  - `object VaultTreeBuilder`:
    - `fun build(encryptedEntries: List<RemoteEntry>, cipher: Cipher, rules: SyncRules = SyncRules()): DecryptedVaultSnapshot`
    - `fun filterNotes(notes: List<VaultTreeNode.File>, query: String): List<VaultTreeNode.File>`
    - `fun resolveWikilink(notes: List<VaultTreeNode.File>, target: String): VaultTreeNode.File?`

- [ ] **Step 1: Write the failing test in `browser-extension/src/jsTest/kotlin/dev/mimir/extension/vault/VaultTreeBuilderTest.kt`**

```kotlin
package dev.mimir.extension.vault

import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.extension.platform.ExtensionTokenStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class VaultTreeBuilderTest {
    @Test
    fun buildsSortedHierarchySkipsExcludedAndUnencryptedGarbage() = runTest {
        val cipher = RcloneCipher.create("test-pass")
        fun encPath(vararg segments: String) = RemotePath.of(segments.map(cipher::encryptName))

        val entries = listOf(
            RemoteEntry(encPath("Проекты", "Mimir", "Архитектура.md"), size = 120, modifiedAt = Instant.fromEpochSeconds(1700000100), revision = "1", isDirectory = false),
            RemoteEntry(encPath("Инбокс.md"), size = 50, modifiedAt = Instant.fromEpochSeconds(1700000200), revision = "2", isDirectory = false),
            RemoteEntry(encPath(".obsidian", "workspace.json"), size = 10, modifiedAt = Instant.fromEpochSeconds(1700000300), revision = "3", isDirectory = false),
            RemoteEntry(encPath(".trash", "Удаленная.md"), size = 10, modifiedAt = Instant.fromEpochSeconds(1700000400), revision = "4", isDirectory = false),
            RemoteEntry(RemotePath(".mimir_canary"), size = 42, modifiedAt = Instant.fromEpochSeconds(1700000000), revision = "5", isDirectory = false),
            RemoteEntry(RemotePath("not-valid-base32-ciphertext!!!"), size = 99, modifiedAt = Instant.fromEpochSeconds(1700000500), revision = "6", isDirectory = false),
        )

        val snapshot = VaultTreeBuilder.build(entries, cipher)
        assertEquals(2, snapshot.flatNotes.size)
        assertEquals(listOf("Проекты/Mimir/Архитектура.md", "Инбокс.md"), snapshot.flatNotes.map { it.relativePath })

        val rootFolder = snapshot.tree.first() as VaultTreeNode.Folder
        assertEquals("Проекты", rootFolder.name)
        val rootFile = snapshot.tree[1] as VaultTreeNode.File
        assertEquals("Инбокс.md", rootFile.name)
    }

    @Test
    fun filtersByNameAndPathAndResolvesWikilinks() = runTest {
        val cipher = RcloneCipher.create("test-pass")
        fun encPath(vararg segments: String) = RemotePath.of(segments.map(cipher::encryptName))

        val entries = listOf(
            RemoteEntry(encPath("Проекты", "Деплой ключей.md"), size = 100, modifiedAt = Instant.fromEpochSeconds(100), revision = "1", isDirectory = false),
            RemoteEntry(encPath("Личное", "Книги.md"), size = 80, modifiedAt = Instant.fromEpochSeconds(200), revision = "2", isDirectory = false),
        )
        val snapshot = VaultTreeBuilder.build(entries, cipher)

        val filtered = VaultTreeBuilder.filterNotes(snapshot.flatNotes, "прое")
        assertEquals(1, filtered.size)
        assertEquals("Проекты/Деплой ключей.md", filtered.single().relativePath)

        assertNotNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Деплой ключей"))
        assertNotNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Проекты/Деплой ключей|Мой алиас"))
        assertNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Несуществующая"))
    }

    @Test
    fun extensionTokenStorageSplitsAccessInSessionAndRefreshInLocal() = runTest {
        val platform = FakeExtensionPlatform()
        val storage = ExtensionTokenStorage(platform)
        val tokens = OAuthTokens(
            accessToken = "acc-1",
            refreshToken = "ref-1",
            expiresAtEpochSeconds = 999999L,
            scope = "drive.file",
            tokenType = "Bearer",
            clientKind = GoogleClientKind.Desktop,
        )
        storage.save(tokens)
        assertEquals("acc-1", storage.load()?.accessToken)
        assertEquals("ref-1", storage.load()?.refreshToken)

        platform.clearSession()
        val afterSessionClear = storage.load()
        assertNotNull(afterSessionClear)
        assertEquals("", afterSessionClear.accessToken)
        assertEquals(0L, afterSessionClear.expiresAtEpochSeconds)
        assertEquals("ref-1", afterSessionClear.refreshToken)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :browser-extension:jsTest`
Expected: FAIL with compilation error (`vault` and `platform` packages do not exist).

- [ ] **Step 3: Configure `gradle.properties`, `browser-extension/build.gradle.kts`, and implement `ExtensionPlatform`, `FakeExtensionPlatform`, and `VaultTreeBuilder`**

1. In `gradle.properties`: add `mimir.extensionPublicKey=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0mimirQuickViewExtensionKey000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000IDAQAB`.
2. In `browser-extension/build.gradle.kts`:
   - Add plugins `kotlin("plugin.serialization")` and `alias(libs.plugins.buildconfig)`.
   - Configure `buildConfig` (`packageName("dev.mimir.extension")`, `className("ExtensionBuildConfig")`) with `GOOGLE_DESKTOP_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_SECRET`, `GOOGLE_DEVICE_CLIENT_ID`, `GOOGLE_DEVICE_CLIENT_SECRET` reading `MIMIR_BUILD_GOOGLE_*` with `.orElse(providers.environmentVariable("MIMIR_GOOGLE_*")).orElse("")`.
   - In `jsMain.dependencies`: add `implementation(project(":drive"))`, `implementation(libs.ktor.client.core)`, `implementation(libs.ktor.client.js)`, `implementation(libs.ktor.client.content.negotiation)`, `implementation(libs.ktor.serialization.json)`, `implementation(libs.serialization.json)`, `implementation(libs.coroutines.core)`.
   - In `jsTest.dependencies`: add `implementation(kotlin("test"))`, `implementation(libs.coroutines.test)`.
3. Implement `ExtensionPlatform` and `ExtensionTokenStorage` in `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt`:
   - `ExtensionTokenStorage` saves `accessToken` and `expiresAtEpochSeconds` in `session` (`session.accessToken`, `session.expiresAt`) and `refreshToken`, `scope`, `tokenType`, `clientKind` in `local` (`auth.refreshToken`, `auth.clientKind`, `auth.scope`). When `session.accessToken` is missing after browser restart, `load()` returns `OAuthTokens(accessToken = "", refreshToken = refresh, expiresAtEpochSeconds = 0L, ...)` so `RefreshingTokenSource` automatically refreshes the token via `refreshToken`.
4. Implement `VaultTreeBuilder` in `browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt`:
   - Decrypt each segment of `RemoteEntry.path` with `cipher.decryptName(segment)` inside `try / catch (e: CryptException)` (skipping any entry that fails decryption or equals `.mimir_canary`).
   - Exclude paths matching `rules.isExcluded(decryptedPath)` or starting with `.obsidian/` or `.trash/`.
   - Build nested `VaultTreeNode.Folder` and `VaultTreeNode.File` sorted with folders first (alphabetically case-insensitive), then files (alphabetically case-insensitive).
   - `resolveWikilink(notes, target)`: strip `|alias` and `#heading`, strip optional `.md` suffix, match first by exact case-insensitive relative path without `.md`, then by file name without `.md`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :browser-extension:jsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add gradle.properties browser-extension/build.gradle.kts browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ExtensionPlatform.kt browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/VaultTreeBuilder.kt browser-extension/src/jsTest/kotlin/dev/mimir/extension/platform/FakeExtensionPlatform.kt browser-extension/src/jsTest/kotlin/dev/mimir/extension/vault/VaultTreeBuilderTest.kt
rtk oco
```

---

### Task 3: Контроллер состояния `ExtensionController` (OAuth, разблокировка, кэш дерева и чтение заметок)

**Files:**
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionUiState.kt`
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt`
- Test: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/ExtensionControllerTest.kt`

**Interfaces:**
- Consumes: `ExtensionPlatform`, `ExtensionTokenStorage`, `VaultTreeBuilder`, `VaultTreeNode`, `CanaryVerifier`, `RcloneCipher`, `EncryptedRemoteStorage`, `RemoteStorage`, `GoogleAuthClient`
- Produces:
  - `sealed interface ExtensionUiState` (`Initializing`, `AuthRequired`, `UnlockRequired`, `VaultLoading`, `VaultReady`)
  - `sealed interface ActiveNoteState` (`Loading`, `Loaded`, `Error`)
  - `data class DeviceCodeUiPrompt(val userCode: String, val verificationUrl: String)`
  - `class ExtensionController(...)`:
    - `val state: StateFlow<ExtensionUiState>`
    - `suspend fun initialize(initialPath: String? = null)`
    - `suspend fun signInWithWebAuthFlow()`
    - `suspend fun startDeviceCodeFlow()`
    - `suspend fun signOut()`
    - `suspend fun unlock(password: String)`
    - `suspend fun lock()`
    - `suspend fun refreshVault()`
    - `suspend fun openNote(file: VaultTreeNode.File)`
    - `suspend fun openWikilink(target: String)`
    - `suspend fun closeActiveNote()`
    - `fun updateSearchQuery(query: String)`
    - `fun toggleFolder(folderPath: String)`

- [ ] **Step 1: Write the failing test in `browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/ExtensionControllerTest.kt`**

```kotlin
package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.CountingRemoteStorage
import dev.mimir.core.testing.InMemoryRemoteStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionControllerTest {
    @Test
    fun fullLifecycleUnlockBrowseReadBackAndLockSharesSessionKey() = runTest {
        val cipher = RcloneCipher.create("master-pass")
        val rawRemote = InMemoryRemoteStorage()
        rawRemote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        val noteEncPath = RemotePath.of(listOf("Проекты", "План.md").map(cipher::encryptName))
        rawRemote.write(
            noteEncPath,
            cipher.encrypt(Buffer().apply { writeString("# План\nСм. [[Вторая]]") }),
        )
        val secondEncPath = RemotePath.of(listOf("Вторая.md").map(cipher::encryptName))
        rawRemote.write(
            secondEncPath,
            cipher.encrypt(Buffer().apply { writeString("Текст второй заметки") }),
        )

        val countingRemote = CountingRemoteStorage(rawRemote)
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "user@example.com", displayName = "User"))

        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { countingRemote },
            authHandler = FakeExtensionAuthHandler(platform),
        )

        controller.initialize()
        val unlockState = assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertEquals("user@example.com", unlockState.account.email)

        // Неверный пароль отклоняется
        controller.unlock("wrong-pass")
        val failedUnlock = assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertNotNull(failedUnlock.error)

        // Верный пароль загружает дерево заметок
        controller.unlock("master-pass")
        val ready = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertEquals(2, ready.flatNotes.size)
        assertNull(ready.activeNote)

        // Открытие заметки (Вариант А: Список -> Заметка)
        val planFile = ready.flatNotes.first { it.name == "План.md" }
        controller.openNote(planFile)
        val withNote = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        val loaded = assertIs<ActiveNoteState.Loaded>(withNote.activeNote)
        assertEquals("# План\nСм. [[Вторая]]", loaded.rawMarkdown)

        // Переход по [[Вторая]] wikilink
        controller.openWikilink("Вторая")
        val afterWiki = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        val loadedSecond = assertIs<ActiveNoteState.Loaded>(afterWiki.activeNote)
        assertEquals("Текст второй заметки", loadedSecond.rawMarkdown)

        // Возврат к списку кнопкой «← Назад»
        controller.closeActiveNote()
        val backToList = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertNull(backToList.activeNote)

        // Открытие второй вкладки (новый инстанс контроллера поверх того же platform):
        // 0 сетевых вызовов listAll и без повторного ввода пароля!
        val listCallsBeforeSecondTab = countingRemote.listCalls
        val tabController = ExtensionController(
            platform = platform,
            remoteStorageFactory = { countingRemote },
            authHandler = FakeExtensionAuthHandler(platform),
        )
        tabController.initialize(initialPath = "Проекты/План.md")
        val tabReady = assertIs<ExtensionUiState.VaultReady>(tabController.state.value)
        assertEquals(listCallsBeforeSecondTab, countingRemote.listCalls)
        val tabNote = assertIs<ActiveNoteState.Loaded>(tabReady.activeNote)
        assertEquals("# План\nСм. [[Вторая]]", tabNote.rawMarkdown)

        // Блокировка очищает сессию, но оставляет аккаунт и зашифрованный кэш
        controller.lock()
        assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertNull(platform.getSessionString("session.cipherKeyHex"))
        assertNotNull(platform.getLocalString("vault.encryptedEntries"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :browser-extension:jsTest --tests "dev.mimir.extension.ui.ExtensionControllerTest"`
Expected: FAIL with unresolved references `ExtensionUiState`, `ExtensionController`.

- [ ] **Step 3: Implement `ExtensionUiState` and `ExtensionController`**

1. In `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionUiState.kt`: define `ExtensionUiState`, `ActiveNoteState`, `DeviceCodeUiPrompt` matching §7.1 of the spec.
2. In `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt`:
   - Define `interface ExtensionAuthHandler` (`suspend fun currentAccount(): Account?`, `suspend fun signInWithWebAuthFlow(): Account`, `suspend fun startDeviceCode(onPrompt: (DeviceCodeUiPrompt) -> Unit): Account`, `suspend fun signOut()`) and its production implementation `GoogleExtensionAuthHandler` using `GoogleAuthClient` and `Pkce` from `:drive`.
   - Implement `initialize(initialPath: String?)`:
     - Check `authHandler.currentAccount()`. If `null`, transition to `AuthRequired()`.
     - Check `platform.getSessionString("session.cipherKeyHex")`. If `null`, transition to `UnlockRequired(account)`.
     - Restore `RcloneCipher.fromKeyHex(keyHex)`. If `vault.encryptedEntries` is present in `chrome.storage.local`, deserialize cached `List<CachedRemoteEntry>`, build `VaultTreeBuilder.build(entries, cipher)`, and transition directly to `VaultReady` (0 network calls); otherwise call `refreshVault()`.
     - If `initialPath` (or `session.selectedPath`) matches a file in `flatNotes`, call `openNote(file)`.
   - Implement `unlock(password: String)`:
     - Derive `cipher = RcloneCipher.create(password)`.
     - Verify password via `CanaryVerifier.verifyReadOnly(rawStorage, cipher, platform.getLocalString("vault.canaryHash"))`.
     - On `Invalid`, update `UnlockRequired(account, isVerifying = false, error = result.reason)`.
     - On `Valid`, store `session.cipherKeyHex = cipher.exportKeyHex()` and `vault.canaryHash = result.canaryHash`, then load tree from cache or `refreshVault()`.
   - Implement `openNote(file)` using `EncryptedRemoteStorage(rawStorage, cipher).read(RemotePath.of(file.relativePath.split("/"))).readString()`, saving `session.selectedPath = file.relativePath`.
   - Implement `closeActiveNote()` removing `session.selectedPath` and setting `activeNote = null`.
   - Implement `lock()` calling `platform.clearSession()` and transitioning to `UnlockRequired(account)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :browser-extension:jsTest --tests "dev.mimir.extension.ui.ExtensionControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionUiState.kt browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/ExtensionControllerTest.kt
rtk oco
```

---

### Task 4: Безопасный DOM-рендерер Markdown (`SafeMarkdownRenderer`)

**Files:**
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt`
- Test: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/markdown/SafeMarkdownRendererTest.kt`

**Interfaces:**
- Consumes: `org.w3c.dom.Document`, `org.w3c.dom.HTMLElement`
- Produces:
  - `object SafeMarkdownRenderer`:
    - `fun render(document: Document, markdown: String, onWikilinkClick: (String) -> Unit = {}, onCopyCode: (String) -> Unit = {}): HTMLElement`
    - `fun stripMarkdownToPlainText(markdown: String): String`

- [ ] **Step 1: Write the failing test in `browser-extension/src/jsTest/kotlin/dev/mimir/extension/markdown/SafeMarkdownRendererTest.kt`**

```kotlin
package dev.mimir.extension.markdown

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeMarkdownRendererTest {
    @Test
    fun rendersHeadingsListsCodeTablesAndWikilinksViaCreateElement() {
        var clickedWiki: String? = null
        val md = """
            # Заголовок 1
            Абзац с **жирным**, `кодом` и ссылкой [[Проекты/Архитектура|Архитектура]].

            - [x] Готово
            - [ ] В работе

            | Ключ | Значение |
            | --- | --- |
            | mode | sidePanel |

            ```kotlin
            val x = 42
            ```
        """.trimIndent()

        val root = SafeMarkdownRenderer.render(document, md, onWikilinkClick = { clickedWiki = it })
        assertEquals(" Заголовок 1".trim(), root.querySelector("h1")?.textContent)
        assertEquals("жирным", root.querySelector("strong")?.textContent)
        assertEquals("кодом", root.querySelector("p code")?.textContent)
        assertEquals(2, root.querySelectorAll("input[type='checkbox']").length)
        assertEquals("sidePanel", root.querySelector("table tbody td:last-child")?.textContent)
        assertTrue(root.querySelector("pre code")?.textContent?.contains("val x = 42") == true)

        val wikilink = root.querySelector("a.wikilink") as? HTMLElement
        assertNotNull(wikilink)
        assertEquals("Архитектура", wikilink.textContent)
        wikilink.click()
        assertEquals("Проекты/Архитектура|Архитектура", clickedWiki)
    }

    @Test
    fun blocksRawHtmlScriptsAndUnsafeUrlSchemes() {
        val malicious = """
            <script>window.pwned = true;</script>
            <img src=x onerror=alert(1)>
            [Плохая ссылка](javascript:alert(1))
            [Data ссылка](data:text/html,<script>alert(1)</script>)
            [Хорошая ссылка](https://example.com/docs)
        """.trimIndent()

        val root = SafeMarkdownRenderer.render(document, malicious)
        assertNull(root.querySelector("script"))
        assertNull(root.querySelector("img"))
        assertTrue(root.textContent?.contains("<script>window.pwned = true;</script>") == true)

        val anchors = root.querySelectorAll("a")
        assertEquals(1, anchors.length)
        val safeAnchor = anchors.item(0) as HTMLAnchorElement
        assertEquals("https://example.com/docs", safeAnchor.href)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :browser-extension:jsTest --tests "dev.mimir.extension.markdown.SafeMarkdownRendererTest"`
Expected: FAIL with unresolved reference `SafeMarkdownRenderer`.

- [ ] **Step 3: Implement `SafeMarkdownRenderer` in `browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt`**

- Construct all DOM nodes strictly via `document.createElement` and `document.createTextNode` (never assigning `innerHTML`).
- Parse block structures line-by-line: fenced code blocks (```` ``` ````), headings (`#`..`######`), GFM tables (`| col |`), blockquotes (`>`), unordered/ordered lists and task items (`- [ ]`, `- [x]`), horizontal rules (`---`), and paragraphs.
- Parse inline tokens: `[[target|label]]` / `[[target]]` (`<a class="wikilink">`), `[label](url)` (allow only `https://`, `http://`, `mailto:`; render other schemes as plain text), `` `code` ``, `**bold**`, `*italic*`, `~~strike~~`.
- Implement `stripMarkdownToPlainText(markdown: String): String` stripping formatting syntax for the «Скопировать текст» button.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :browser-extension:jsTest --tests "dev.mimir.extension.markdown.SafeMarkdownRendererTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt browser-extension/src/jsTest/kotlin/dev/mimir/extension/markdown/SafeMarkdownRendererTest.kt
rtk oco
```

---

### Task 5: Представление DOM (`PopupDomView`), адаптивная вёрстка Side Panel / Reader Tab и манифест MV3

**Files:**
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ChromeExtensionPlatform.kt`
- Create: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt`
- Modify: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt`
- Modify: `browser-extension/src/jsMain/resources/popup.html`
- Create: `browser-extension/src/jsMain/resources/popup.css`
- Modify: `browser-extension/manifest.template.json`
- Modify: `browser-extension/build.gradle.kts`
- Modify: `browser-extension/scripts/check-extension.mjs`
- Test: `browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/PopupDomViewTest.kt`

**Interfaces:**
- Consumes: `ExtensionController`, `ExtensionUiState`, `SafeMarkdownRenderer`, `ExtensionPlatform`
- Produces:
  - `class ChromeExtensionPlatform : ExtensionPlatform` (Kotlin/JS `external` bindings for `chrome.storage.session`, `chrome.storage.local`, `chrome.identity`, `chrome.tabs`, `chrome.sidePanel`)
  - `class PopupDomView(private val root: HTMLElement, private val controller: ExtensionController, private val platform: ExtensionPlatform, private val isFullTabMode: Boolean)`
  - Updated `manifest.template.json`, `popup.html`, `popup.css`, and `check-extension.mjs`

- [ ] **Step 1: Write the failing test in `browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/PopupDomViewTest.kt`**

```kotlin
package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.InMemoryRemoteStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.browser.document
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopupDomViewTest {
    @Test
    fun sidePanelModeSwitchesMasterDetailWhileTabModeRendersTwoColumns() = runTest {
        val cipher = RcloneCipher.create("pass")
        val remote = InMemoryRemoteStorage()
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        remote.write(
            RemotePath.of(listOf("Архитектура.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# Заголовок заметки") }),
        )

        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "dev@mimir.io", displayName = "Dev"))
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { remote },
            authHandler = FakeExtensionAuthHandler(platform),
        )
        controller.initialize()
        controller.unlock("pass")

        // 1. Узкий режим Side Panel (isFullTabMode = false): сначала только список, без панели чтения
        val sidePanelContainer = document.createElement("div") as HTMLElement
        val sidePanelView = PopupDomView(sidePanelContainer, controller, platform, isFullTabMode = false)
        sidePanelView.render(controller.state.value)

        assertNotNull(sidePanelContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNull(sidePanelContainer.querySelector("[data-testid='note-reader-pane']"))

        // Открываем заметку -> в Side Panel отображается только экран чтения с кнопкой «← Назад»
        val noteFile = (controller.state.value as ExtensionUiState.VaultReady).flatNotes.single()
        controller.openNote(noteFile)
        sidePanelView.render(controller.state.value)

        assertNull(sidePanelContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNotNull(sidePanelContainer.querySelector("[data-testid='note-reader-pane']"))
        assertNotNull(sidePanelContainer.querySelector("[data-testid='back-to-list-btn']"))

        // 2. Полноэкранная вкладка (isFullTabMode = true): обе колонки рендерятся одновременно
        val tabContainer = document.createElement("div") as HTMLElement
        val tabView = PopupDomView(tabContainer, controller, platform, isFullTabMode = true)
        tabView.render(controller.state.value)

        assertNotNull(tabContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNotNull(tabContainer.querySelector("[data-testid='note-reader-pane']"))
        assertTrue(tabContainer.textContent?.contains("Заголовок заметки") == true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :browser-extension:jsTest --tests "dev.mimir.extension.ui.PopupDomViewTest"`
Expected: FAIL with unresolved reference `PopupDomView`.

- [ ] **Step 3: Implement `ChromeExtensionPlatform`, `PopupDomView`, `Popup.kt`, `popup.html`, `popup.css`, `manifest.template.json`, and update `check-extension.mjs`**

1. In `browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/ChromeExtensionPlatform.kt`: wrap `chrome.storage.session`, `chrome.storage.local`, `chrome.identity.getRedirectURL`, `chrome.identity.launchWebAuthFlow`, `chrome.tabs.create`, `navigator.clipboard.writeText`, and call `chrome.sidePanel?.setPanelBehavior(json("openPanelOnActionClick" to true))` on startup.
2. In `browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt`:
   - Render `ExtensionUiState` (`AuthRequired`, `UnlockRequired`, `VaultLoading`, `VaultReady`) into `root`.
   - When `isFullTabMode == false` (Side Panel / Popup): render either `[data-testid='vault-tree-pane']` (when `activeNote == null`) or `[data-testid='note-reader-pane']` with `[data-testid='back-to-list-btn']` (`← Назад`, when `activeNote != null`).
   - When `isFullTabMode == true` (`?mode=tab`): render both `[data-testid='vault-tree-pane']` and `[data-testid='note-reader-pane']` side-by-side inside `.workspace-split`.
   - Include toolbar buttons: `🔄` (refresh), `↗` (open full tab via `platform.openFullTab`), `🔒` (lock session), `📋 Скопировать MD`, `📄 Скопировать текст`.
3. Update `browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt`: parse `window.location.search` (`mode=tab`, `path=...`), instantiate `ChromeExtensionPlatform`, `ExtensionController`, and `PopupDomView`, collect `controller.state` to update `view.render(state)`, and launch `controller.initialize(initialPath)`.
4. Update `browser-extension/manifest.template.json` and `browser-extension/build.gradle.kts` (`assembleExtension` expands `appName`, `version`, `extensionPublicKey` and copies `popup.css`), and update `browser-extension/scripts/check-extension.mjs` to verify `side_panel.default_path === "popup.html"`, `permissions` (`identity`, `storage`, `sidePanel`), and `popup.css` existence.

- [ ] **Step 4: Run tests and full extension verification**

Run: `rtk ./gradlew :browser-extension:check :core:jvmTest`
Expected: PASS (`jsTest` passes and `checkExtension OK (Mimir Quick View 0.1.0)`).

- [ ] **Step 5: Commit**

```bash
rtk git add browser-extension/
rtk oco
```
