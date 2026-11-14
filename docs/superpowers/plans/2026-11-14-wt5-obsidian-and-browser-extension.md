# Оптимизация, рефакторинг и ускорение тестов JS-клиентов (:apps:obsidian-plugin и :apps:browser-extension, после слияния WT1 + WT2) — Implementation Plan

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: `superpowers:using-superpowers` → `superpowers:executing-plans` → `superpowers:test-driven-development` → `superpowers:verification-before-completion`. Выполняется в изолированном воркспейсе (`Workspace: "share"` в Antigravity или `.worktrees/wt-js-clients` на ветке `refactor/obsidian-and-extension`) поверх `main` с уже влитыми WT1 и WT2. Шаги отмечаются чекбоксами (`- [ ]`). Прогресс фиксировать в `.superpowers/sdd/2026-11-14-wt5-obsidian-and-browser-extension/progress.md`.

**Goal:** Устранить последовательный `fs.stat` и пересоздание `DrivePathResolver` на каждый прогон в `:apps:obsidian-plugin`, починить выход из аккаунта и отображение `email` в настройках плагина, разделить DOM-контейнеры в `PopupDomView` и инкрементально проверять `ChangeFeedStorage` в `:apps:browser-extension`, декомпозировать `SafeMarkdownRenderer` и `ExtensionController`, вычистить тестовые фейки из `jsMain` и ускорить JS-тесты обоих модулей в 5–8 раз за счёт общих `TestCiphers.forPassword` и `TestClock` из `:libs:core`.

**Architecture:** Все изменения строго изолированы внутри `apps/obsidian-plugin/src/**` и `apps/browser-extension/src/**`. В `SyncAssembler` и `ExtensionController` используется `CanaryVerifier.verifyOrInit` / `CANARY_BODY` из `:libs:core` (WT2) и внедряется параметр `cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) }`, который в `jsTest` подменяется на `dev.mimir.core.testing.TestCiphers::forPassword` (убирает ~35 повторных прогонов `Scrypt.derive` в однопоточном JS).

**Tech Stack:** Kotlin/JS (IR, `es2015`), Ktor Client 3.6.0, `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-io`, Karma + ChromeHeadless.

**Spec:** `docs/superpowers/specs/2026-11-09-obsidian-plugin-sync-design.md`, `docs/superpowers/specs/2026-11-01-mimir-browser-extension-design.md`

**Worktree / Workspace:** Antigravity `Workspace: "share"` или `.worktrees/wt-js-clients` (ветка `refactor/obsidian-and-extension`)

## Global Constraints

- Строгая граница файлов: изменять **только** `apps/obsidian-plugin/src/**` и `apps/browser-extension/src/**`. Не трогать `libs/**`, `apps/daemon/**`, `apps/desktop/**`, `apps/android/**`, `apps/web-ui/**` и `*.gradle.kts`.
- В JS-коде перехватывать `Throwable` с обязательным пробросом `CancellationException`.
- В `chrome.storage.local` браузерного расширения запрещено хранить расшифрованные имена/тексты заметок или ключ `RcloneCipher` (разрешены только зашифрованные `RemoteEntry`, `.canary_hash` и `changeToken`).
- При запуске `./gradlew` передавать `--max-workers=4` (для защиты лимита 16 ГБ ОЗУ при параллельной работе с WT3 и WT4), а в конце всей работы выполнить `rtk ./gradlew --stop`.
- Все терминальные команды выполняются строго с префиксом `rtk`; навигация по коду — через `rtk context-init pack` и `rtk rg`.

## Инварианты Mimir

1. **Платформы:**
   - **Obsidian (Desktop, iOS, Android):** батчинг `fs.stat` по 64 файла ускоряет сканирование больших хранилищ; кэширование `DrivePathResolver` между прогонами сохраняет прогретую таблицу `path -> fileId`; кнопка «Sign out» корректно отзывает токен в Google и очищает индекс, а в настройках отображается реальный `email`.
   - **Chromium MV3 (`:apps:browser-extension`):** ввод в строку поиска и переключение папок обновляют только дерево файлов (`treeContainer`), не сбрасывая DOM открытой заметки (`readerContainer`); `refreshVault()` проверяет `getChanges(changeToken)` перед полным `listAll()`; продакшн-бандл `popup.js` очищен от `FakeExtensionPlatform` и `FakeExtensionAuthHandler`.
2. **Дизайн:**
   - Декомпозиция `SafeMarkdownRenderer.kt` (381 строка → `MarkdownBlockRenderer.kt` + `MarkdownInlineRenderer.kt`), `ExtensionController.kt` (368 строк → выделение `ExtensionVaultCache.kt`) и `MimirPlugin.kt` (выделение `registerVaultListeners`, `registerStatusBar`, `registerCommands`).
   - Переиспользование `CanaryVerifier.verifyOrInit` и тестовых двойников `TestCiphers`, `TestClock`, `FakeSyncEngine`, `FakeUpdateHttpClient`, `FakeUpdateChannel` из `dev.mimir.core.testing` (WT2).
3. **IO:**
   - В `ObsidianLocalVault.list()` `fs.stat` идёт параллельными батчами по 64 промиса вместо $N$ последовательных ожиданий; в `write()` результат `fs.stat` читается 1 раз вместо 3.
   - В `SyncAssembler` экземпляр `DrivePathResolver` и `DefaultSyncEngine` переиспользуется между прогонами.
   - В `ExtensionController.refreshVault()` при пустой дельте `getChanges(token)` обход `listAll()` не вызывается.
4. **UX:**
   - Отсутствие моргания и сброса скролла открытой заметки при вводе текста в поиск браузерного расширения; отображение настоящего `email` аккаунта Google в настройках плагина Obsidian.

## Review Focus

1. **Повторный прогон синхронизации в `SyncAssembler`:** при двух подряд вызовах `buildEngine()` с неизменными настройками и ключом возвращается тот же экземпляр `DrivePathResolver`, но при смене `rootFolder` или `clearKey()` создаётся новый.
2. **Выход из аккаунта в `MimirSettingTab`:** нажатие кнопки «Выйти» вызывает `signOut(...)` (отзыв токена + `secrets.clearAll()` + `stateStore.clear()`), сбрасывает закэшированный движок и обновляет вкладку настроек.
3. **Сохранение DOM открытой заметки при поиске в `PopupDomView`:** при вызове `controller.updateSearchQuery("foo")` элемент `.reader-content` открытой заметки не пересоздаётся (`sameNode === true`).
4. **Инкрементальный `refreshVault()` в `ExtensionController`:** если в кэше есть записи и `changeToken`, а `getChanges(changeToken)` вернул пустой список изменений, `listAll()` не вызывается.
5. **Чистота `src/jsMain` в `:apps:browser-extension`:** после переноса `FakeExtensionPlatform` и `FakeExtensionAuthHandler` в `src/jsTest` сборка `:apps:browser-extension:assembleExtension` и проверка `checkExtension` проходят успешно.

## Структура файлов

```
apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/
  MimirPlugin.kt                         (изм.: декомпозиция onload на registerVaultListeners/registerStatusBar/registerCommands)
  net/ObsidianHttpEngine.kt              (изм.: общий internal fun Throwable.isNetworkError(): Boolean)
  vault/ObsidianLocalVault.kt            (изм.: батчинг fs.stat по 64 в list(), одиночный stat в write())
  state/DeviceSecretStore.kt             (изм.: сохранение и чтение accountEmail)
  sync/SyncAssembler.kt                  (изм.: кэширование собранного движка/DrivePathResolver, CanaryVerifier.verifyOrInit, cipherFactory)
  sync/PluginSyncController.kt           (изм.: использование общего isNetworkError())
  sync/SignIn.kt                         (изм.: сохранение email в DeviceSecretStore, использование общего isNetworkError())
  ui/MimirSettingTab.kt                  (изм.: отображение реального email и вызов полноценного signOut)
apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/
  sync/SyncAssemblerTest.kt              (изм.: TestCiphers::forPassword из :libs:core + тест переиспользования движка)
  EndToEndSyncTest.kt, ObsidianPluginTest.kt, sync/PluginSyncControllerTest.kt, sync/SignInTest.kt, vault/ObsidianLocalVaultTest.kt (изм.: TestCiphers, TestClock, FakeSyncEngine, FakeUpdateHttpClient из :libs:core)
apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/
  markdown/SafeMarkdownRenderer.kt       (изм.: <120 строк, фасад над MarkdownBlockRenderer и MarkdownInlineRenderer, удаление stripMarkdownToPlainText)
  markdown/MarkdownBlockRenderer.kt      (созд.: блочный парсер/рендерер заголовков, списков, таблиц, кодовых блоков)
  markdown/MarkdownInlineRenderer.kt     (созд.: инлайновый парсер ссылок, wikilinks, санитизация URL)
  vault/ExtensionVaultCache.kt           (созд.: вынос AccountCodec, CachedRemoteEntry, loadCache, saveCache, changeToken из ExtensionController)
  ui/ExtensionController.kt              (изм.: <260 строк, удаление plainText из ActiveNoteState.Loaded, cipherFactory, проверка getChanges в refreshVault)
  ui/VaultTreeBuilder.kt                 (изм.: предвычисленный lowerPath в VaultTreeNode.File)
  ui/PopupDomView.kt                     (изм.: раздельные DOM-контейнеры treePane и readerPane без полного сброса при поиске)
  ui/GoogleExtensionAuthHandler.kt       (изм.: добавление delay при slow_down)
apps/browser-extension/src/jsTest/kotlin/dev/mimir/extension/
  platform/FakeExtensionPlatform.kt      (пер.: из src/jsMain в src/jsTest)
  ui/FakeExtensionAuthHandler.kt         (пер.: из src/jsMain в src/jsTest)
  ui/ExtensionControllerTest.kt, ExtensionControllerReviewFixesTest.kt, BackButtonTest.kt, PopupDomViewTest.kt, NoteViewToggleTest.kt, RealChromePlatformFlowTest.kt, VaultTreeBuilderTest.kt, update/ExtensionUpdateControllerTest.kt, update/WebExtensionNotifyApplierTest.kt (изм.: TestCiphers::forPassword, TestClock, FakeUpdateChannel из :libs:core)
```

---

### Task 1: Оптимизация `ObsidianLocalVault`, кэширование движка в `SyncAssembler`, починка `signOut`/`email` и перевод тестов `:apps:obsidian-plugin` на `dev.mimir.core.testing`

**Files:**
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/vault/ObsidianLocalVault.kt:51-155`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/net/ObsidianHttpEngine.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/state/DeviceSecretStore.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/SyncAssembler.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/PluginSyncController.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/SignIn.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ui/MimirSettingTab.kt`
- Modify: `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt`
- Modify: `apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/sync/SyncAssemblerTest.kt`, `EndToEndSyncTest.kt`, `ObsidianPluginTest.kt`, `sync/PluginSyncControllerTest.kt`, `sync/SignInTest.kt`, `vault/ObsidianLocalVaultTest.kt`

**Interfaces:**
- Consumes: `CanaryVerifier.verifyOrInit`, `CanaryVerifier.CANARY_BODY`, `TestCiphers.forPassword`, `TestClock`, `FakeSyncEngine`, `FakeUpdateHttpClient` из `:libs:core` (WT2).
- Produces:
  - `internal fun Throwable.isNetworkError(): Boolean` в `ObsidianHttpEngine.kt`
  - `fun DeviceSecretStore.loadEmail(): String?` и `fun DeviceSecretStore.saveEmail(email: String)`
  - Параметр `cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) }` и кэшированный `assembledEngine: AssembledSync?` в `SyncAssembler`

- [ ] **Step 1: Написать тесты в `SyncAssemblerTest.kt` на кэширование собранного движка и `CanaryVerifier.verifyOrInit`**

Подключить `cipherFactory = { TestCiphers.forPassword(it) }` и проверить, что `buildEngine()` переиспользует `DrivePathResolver` между вызовами до смены `rootFolder` или `clearKey()`, а при инициализации пустого хранилища вызывается ровно 1 `remote.stat(canaryPath)` и записывается `CanaryVerifier.CANARY_BODY`.
Run: `rtk ./gradlew :apps:obsidian-plugin:jsBrowserTest --max-workers=4 --tests "dev.mimir.obsidian.sync.SyncAssemblerTest"`
Expected: FAIL.

- [ ] **Step 2: Реализовать оптимизации и исправления в `:apps:obsidian-plugin` и перевести `jsTest` на фейки из `:libs:core`**

1. В `ObsidianLocalVault.kt`: батчинг `fs.stat(norm)` по 64 файла в `list()` и одиночный `stat` в `write()`.
2. В `ObsidianHttpEngine.kt`: вынести `internal fun Throwable.isNetworkError(): Boolean`.
3. В `SyncAssembler.kt`: использовать `CanaryVerifier.verifyOrInit` и `CanaryVerifier.CANARY_BODY`, добавить `cipherFactory` и кэширование `AssembledSync`.
4. В `DeviceSecretStore.kt`, `SignIn.kt` и `MimirSettingTab.kt`: сохранять/отображать `email` и вызывать `signOut(...)` + `assembler.clearKey()`.
5. В `MimirPlugin.kt`: разбить `onload()` на `registerVaultListeners()`, `registerStatusBar()`, `registerCommands()`.
6. В `EndToEndSyncTest.kt`, `ObsidianPluginTest.kt`, `PluginSyncControllerTest.kt`, `SignInTest.kt`, `ObsidianLocalVaultTest.kt`: заменить локальные `TestClock`, `FakeSyncEngine`, `FakeUpdateHttpClient` и `RcloneCipher.create` на `dev.mimir.core.testing.*`.

- [ ] **Step 3: Прогнать `checkPlugin`**

Run: `rtk ./gradlew :apps:obsidian-plugin:checkPlugin --max-workers=4`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add apps/obsidian-plugin/
rtk oco -y
```

---

### Task 2: Декомпозиция `SafeMarkdownRenderer` и `ExtensionController`, перенос фейков в `jsTest`, частичный DOM-рендеринг `PopupDomView` и ускорение тестов `:apps:browser-extension`

**Files:**
- Move: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/platform/FakeExtensionPlatform.kt` → `apps/browser-extension/src/jsTest/kotlin/dev/mimir/extension/platform/FakeExtensionPlatform.kt`
- Move: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/FakeExtensionAuthHandler.kt` → `apps/browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/FakeExtensionAuthHandler.kt`
- Create: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/MarkdownBlockRenderer.kt`
- Create: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/MarkdownInlineRenderer.kt`
- Create: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/vault/ExtensionVaultCache.kt`
- Modify: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/markdown/SafeMarkdownRenderer.kt`
- Modify: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/ExtensionController.kt`
- Modify: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/VaultTreeBuilder.kt`
- Modify: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/PopupDomView.kt`
- Modify: `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui/GoogleExtensionAuthHandler.kt`
- Modify: `apps/browser-extension/src/jsTest/kotlin/dev/mimir/extension/ui/ExtensionControllerTest.kt`, `ExtensionControllerReviewFixesTest.kt`, `BackButtonTest.kt`, `PopupDomViewTest.kt`, `NoteViewToggleTest.kt`, `RealChromePlatformFlowTest.kt`, `VaultTreeBuilderTest.kt`, `update/ExtensionUpdateControllerTest.kt`, `update/WebExtensionNotifyApplierTest.kt`

**Interfaces:**
- Consumes: `TestCiphers.forPassword`, `TestClock`, `FakeUpdateChannel` из `dev.mimir.core.testing` (WT2).
- Produces:
  - `internal class ExtensionVaultCache(private val platform: ExtensionPlatform)`
  - Параметр `cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) }` в `ExtensionController`
  - `ActiveNoteState.Loaded(val path: String, val rawMarkdown: String)` (без поля `plainText`)

- [ ] **Step 1: Переместить `FakeExtensionPlatform.kt` и `FakeExtensionAuthHandler.kt` из `src/jsMain` в `src/jsTest` и написать тесты в `PopupDomViewTest.kt` и `ExtensionControllerTest.kt`**

1. В `PopupDomViewTest.kt`: тест `updating searchQuery preserves existing reader DOM node when activeNote has not changed`.
2. В `ExtensionControllerTest.kt`: тест `refreshVault skips listAll when cached changeToken returns empty changes from ChangeFeedStorage`.
Run: `rtk ./gradlew :apps:browser-extension:jsBrowserTest --max-workers=4`
Expected: FAIL.

- [ ] **Step 2: Реализовать декомпозицию и оптимизации в `:apps:browser-extension` и перевести все `jsTest` на `TestCiphers::forPassword`**

1. Разделить `SafeMarkdownRenderer.kt` на `MarkdownBlockRenderer.kt` и `MarkdownInlineRenderer.kt`.
2. Вынести `AccountCodec`, `CachedRemoteEntry`, `loadCache`, `saveCache` (с `changeToken: String?`) в `ExtensionVaultCache.kt`.
3. В `ExtensionController.kt`: удалить `plainText` из `ActiveNoteState.Loaded`, добавить `cipherFactory`, в `refreshVault()` проверять `getChanges(changeToken)` перед `listAll()`.
4. В `VaultTreeBuilder.kt`: добавить `val lowerPath: String = relativePath.lowercase()` в `VaultTreeNode.File`.
5. В `PopupDomView.kt`: при изменении только `searchQuery` или `expandedFolders` обновлять только дерево/список заметок без пересоздания DOM-узла открытой заметки.
6. В `GoogleExtensionAuthHandler.kt:65`: при `slow_down` добавлять задержку `+5.seconds`.
7. Во всех тестах `apps/browser-extension/src/jsTest/` использовать `TestCiphers.forPassword`, `TestClock` и `FakeUpdateChannel` из `dev.mimir.core.testing`.

- [ ] **Step 3: Прогнать `checkExtension`, проверить дрейф `context-init` и остановить Gradle Daemon**

Run: `rtk ./gradlew :apps:browser-extension:checkExtension --max-workers=4 && rtk context-init check --compact && rtk ./gradlew --stop`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add apps/browser-extension/
rtk oco -y
```
