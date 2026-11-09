# Спецификация: синхронизация в плагине Obsidian (iOS, Android, desktop)

## 1. Контекст и проблема

На iOS у Mimir нет клиента: публикация своего приложения требует платного Apple Developer Program, а бесплатный sideload живёт 7 дней. Obsidian на iOS бесплатно загружает сторонние плагины (JS внутри WebView самого Obsidian), поэтому плагин — единственный бесплатный путь синхронизировать vault на iPhone с шифрованием на самом устройстве.

Сейчас `apps/obsidian-plugin` — заготовка (228 строк): строка статуса на `StubSyncEngine` и автообновление. `isDesktopOnly: false` уже выставлен. При этом `libs/core` (шифрование `RcloneCipher`/`EncryptedRemoteStorage`, `SyncPlanBuilder`, `DefaultSyncEngine`) и `libs/drive` (Drive API, `DriveUploader` с докачкой, OAuth вместе с Device Code) уже собираются под `js`, а браузерное расширение собирает из них автономный клиент (`apps/browser-extension/.../Popup.kt`). Это эталон для плагина.

Ограничения iOS:
- нет Node: loopback-сервер для OAuth не поднять, а свою URI-схему Google не пропустит;
- CORS в WebView (`capacitor://localhost`);
- iOS замораживает JS через несколько секунд после ухода в фон, поэтому фоновой синхронизации нет;
- доступ к файлам только через Obsidian `DataAdapter`;
- нет Keychain;
- WebView падает примерно от 100–200 МБ в памяти.

## 2. Цели и границы

**Цели:**
- Плагин сам синхронизирует vault ↔ папку Google Drive в формате, совместимом с демоном и расширением (rclone crypt, шифрование путей по сегментам, `.canary_hash`), на iOS, Android и desktop из одного бандла.
- Файлы любого размера передаются потоком: в памяти не больше одного куска на поток.
- На десктопе, где демон Mimir обслуживает этот же vault, плагин не синхронизирует, а показывает статус демона.
- Критерий успеха: на iPhone заметки и вложения больше 200 МБ двусторонне синхронизируются с vault десктопа через Drive, Obsidian не падает.

**Вне границ:**
- Фоновая синхронизация при закрытом Obsidian (на iOS невозможна).
- Продолжение прерванной загрузки по сохранённому session URI: в первой версии загрузка начинается заново, продолжение — следующий шаг.
- Публикация в каталоге Community Plugins: в первой версии GitHub Releases + BRAT, каталог — позже.
- Синхронизация `<configDir>/` (настройки Obsidian разные на разных устройствах).
- Другие облака, кроме Google Drive.

## 3. Рассмотренные подходы

HTTP и потоки:
- **A. Свой Ktor-движок поверх `requestUrl` + скачивание кусками по `Range` — выбран.** `requestUrl` обходит CORS; память ограничена размером куска; поведение одинаково на всех платформах.
- B. Обычный `fetch` с потоковым телом: настоящий поток, но зависит от CORS Google для `capacitor://localhost` (заголовок `Location` при загрузке с докачкой, `oauth2.googleapis.com`). Это не проверено и может незаметно сломаться с обновлением iOS.
- C. Гибрид A+B: два пути кода ради выигрыша в скорости, которого не видно. Отклонён (YAGNI).

Крупные файлы: рассматривался жёсткий лимит размера с пропуском файла. Выбрана потоковая передача: Range при скачивании, блоки при загрузке, `appendBinary` при записи.

Вход: только Device Code. Loopback невозможен, а custom scheme Google разрешает лишь клиенту типа iOS с bundle id Obsidian, а не Mimir.

## 4. Детальный дизайн

### 4.1 Компоненты

Зависимости: `apps/obsidian-plugin` → `libs/drive` → `libs/core`. В библиотеки попадает только то, что не привязано к Obsidian.

**`libs/drive` (commonMain):**
- `GoogleDriveStorage(…, downloadChunkBytes: Long? = null)`.
  - При `null` поведение текущее: один GET `alt=media` потоком в `ByteChannel`.
  - При заданном значении — стратегия кусков: последовательные GET с `Range: bytes=<from>-<to>` в тот же канал, пока не получен последний байт (по `Content-Range`) или пустой ответ / `416` (пустой файл).
  - Каждый кусок идёт через `DriveHttpClient`: 401 → refresh → повтор куска с того же смещения; backoff на 429/`rateLimitExceeded` уже есть.
  - Отмена корутины отменяет цепочку и закрывает канал.

**`apps/obsidian-plugin` (`dev.mimir.obsidian`):**

| Компонент | Порт | Реализация |
|---|---|---|
| `ObsidianHttpEngine` | Ktor `HttpClientEngine` | `requestUrl({url, method, headers, body: ArrayBuffer, throw: false})`. Ответ без статуса (нет сети) → исключение, которое `DriveHttpClient` превращает в `RemoteUnavailableException` |
| `ObsidianLocalVault` | `LocalVault` | Метаданные — `adapter.list`/`stat` рекурсивно. Чтение — `fetch(adapter.getResourcePath(path))` с потоковым телом → `Source`. Запись — временный `.<имя>.mimir-tmp`: первый блок `writeBinary`, следующие `appendBinary`, затем `rename` поверх цели. Удаление — `adapter.remove`. `changes()` — из `vault.on("create"/"modify"/"delete"/"rename")`. `hiddenPaths()` — `<configDir>` |
| `ObsidianSyncStateStore` | `SyncStateStore` | JSON `SyncIndex` в `<configDir>/plugins/<pluginId>/index.json`. Атомарно: запись во временный файл + `rename`. Change token хранится там же |
| `DeviceSecretStore` | `SecretStore`, `TokenStorage` | `app.saveLocalStorage`/`loadLocalStorage` (на устройстве и для каждого vault, вне файлов vault): OAuth-токены и 80-байтный ключ cipher (base64). Пароль не хранится |
| `DaemonProbe` | — | Только при `Platform.isDesktopApp`: `requestUrl` к `GET 127.0.0.1:<mimir.apiDefaultPort>/api/v1/status` (поле `vaultPath`), тайм-аут 1 с. Совпадение нормализованного пути с `adapter.getBasePath()` → режим «уступаю демону». Демон отвечает 401 (включён `MimirTokenAuth`) → путь неизвестен, плагин тоже уступает со статусом «Mimir Desktop запущен; синхронизация плагина приостановлена» и переключателем «Всё равно синхронизировать этим плагином» в настройках. Нет ответа → плагин синхронизирует сам |
| `PluginSyncController` | UDF, `StateFlow<PluginState>` | Сборка движка, триггеры, склейка, отмена, одна синхронизация за раз |
| `PluginTexts` | — | Все строки интерфейса; английский по умолчанию, русский по `moment.locale()` |
| UI | — | `MimirSettingTab`, `DeviceCodeModal`, `ConflictsModal`, статус-бар, ribbon, команды |

**Composition root** — `MimirPlugin.onload` по образцу `Popup.kt`: `HttpClient(ObsidianHttpEngine)` → `DriveHttpClient(tokenStorage = DeviceSecretStore, clients)` → `GoogleDriveStorage(downloadChunkBytes = 8 MiB)` → `EncryptedRemoteStorage(RcloneCipher)` → `DefaultSyncEngine(ObsidianLocalVault, remote, ObsidianSyncStateStore, parallelism = 2, dispatcher = Dispatchers.Default)`. `StubSyncEngine` из плагина удаляется; `PluginUpdater` остаётся.

**Правила:** всегда исключаются `<configDir>/**` и временные `*.mimir-tmp`; остальное — существующие `SyncRules`/`VaultPathFilter`.

**OAuth-клиент:** Device-пара вшивается при сборке в сгенерированный `PluginBuildConfig` из `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID/SECRET` (fallback — `MIMIR_GOOGLE_DEVICE_*` из `mise.local.toml`), по аналогии с BuildConfig `apps/android`. В `libs/core` не попадает. Без секретов сборка проходит; тогда в настройках доступен раздел «Свой OAuth-клиент» (Client ID / Secret), без него вход неактивен с объяснением. Идентификаторы (`mimir.obsidianPluginId`, `mimir.apiDefaultPort`) берутся из `gradle.properties` через генерацию, литералами не дублируются.

### 4.2 Поток данных одного прогона

1. Десктоп: `DaemonProbe`. Демон обслуживает vault → статус `Mimir: handled by Mimir Desktop`, конец прогона.
2. Нет токенов, ключа или папки → `Mimir: not connected`, ни одного сетевого вызова.
3. Удалённые изменения: есть change token → `getChanges(token)`; нет токена или он недействителен → `listAll` (сбрасывает кэш путей).
4. Локальный список — только метаданные (`list`/`stat`), содержимое не читается.
5. `SyncPlanBuilder` → `DefaultSyncEngine`. Существующие защиты действуют: пустой удалённый список при непустом индексе → `SyncAbortedException`; `stat` перед каждым `DeleteLocal`; удаление в Drive — перенос в корзину.
6. Индекс сохраняется каждые 100 файлов и в конце; change token — только после успешного прогона.

Потоки байтов:
- **Скачивание:** Range-куски по 8 МиБ → `cipher.decrypt` блоками по 64 КиБ → `appendBinary` во временный файл → `rename`. Obsidian не видит недописанный файл.
- **Загрузка:** `fetch(getResourcePath)` потоком → `cipher.encrypt` → `DriveUploader` (multipart до 5 МиБ, дальше блоки по 1 МиБ через `requestUrl`).
- **Память:** O(8 МиБ × parallelism).

### 4.3 Триггеры (`PluginSyncController`)

Работают только пока приложение открыто:
- `workspace.onLayoutReady` → прогон.
- `visibilitychange → visible` → прогон, если с прошлого прогона прошло больше 30 с.
- События vault → прогон через 5 с после последнего события. Записи самого плагина отсекаются `EchoFilter`.
- Таймер 5 минут, пока страница видима.
- Команда «Mimir: Sync now», клик по статус-бару или ribbon.
- Триггеры во время прогона склеиваются в один повторный прогон после него.

Уход в фон (`visibilitychange → hidden`): кооперативная отмена, индекс сохраняется сразу, временные файлы удаляются. Если iOS заморозила JS посреди запроса, после возврата запрос падает, файл попадает в проблемы и повторяется в следующем прогоне.

### 4.4 Вход и ключ

**`DeviceCodeModal`:**
- крупный код, кнопка «Скопировать код и открыть google.com/device», таймер истечения;
- опрос с интервалом от Google;
- состояния: ожидание / успех (email) / код истёк («Получить новый») / отказ / офлайн.

«Выйти»: отзыв токена, очистка токенов, ключа, индекса и change token.

**Ключ:**
- Пользователь вводит папку Drive, пароль и необязательную соль rclone.
- scrypt на `Dispatchers.Default` с индикатором «Проверяю пароль…».
- Проверка `.canary_hash`:
  - нет канарейки и папка пуста → создаётся (первое устройство);
  - не совпала → «Пароль не подходит к этой папке», синхронизация не запускается.
- Ключ хранится в `DeviceSecretStore`. Ограничение (в README): плагины Obsidian делят один JS-контекст, и изоляции localStorage от других плагинов нет.

### 4.5 Ошибки

В JS ловится `Throwable` с обязательным пробросом `CancellationException`.

| Ситуация | Поведение |
|---|---|
| Нет сети | `RemoteUnavailableException` → `SyncStatus.Offline` (`Mimir: offline`), повтор по следующему триггеру |
| 401 после refresh | «Нужно войти снова», синхронизация остановлена до входа |
| 429 / `rateLimitExceeded` | backoff `DriveHttpClient` |
| Ошибка одного файла | в проблемы, прогон продолжается, повтор в следующем прогоне |
| `SyncAbortedException` | Notice «Drive пуст, а индекс нет — синхронизация остановлена, ничего не удалено» + «Подробнее» |
| Конфликт | `DefaultConflictResolver`, как у демона; счётчик в статусе, `ConflictsModal` |
| Демон обслуживает vault | режим «уступаю», без ошибки |
| Нет `appendBinary` (старый Obsidian) | см. 4.7: Ruling по итогам проверки |

### 4.6 UX

Родные компоненты Obsidian (`Setting`, `Modal`, `Notice`, CSS-переменные темы): плагин выглядит частью Obsidian в светлой и тёмной теме.

**Статус** (статус-бар на desktop; на мобильных — ribbon и команда):
- `Mimir: not connected` (ровно так, `SyncStatus.label()`)
- `Mimir: syncing 12/340`
- `Mimir: up to date · 2 min ago`
- `Mimir: offline`
- `Mimir: 3 conflicts`
- `Mimir: handled by Mimir Desktop`

**Настройки** — три блока, следующий активен, когда готов предыдущий:
1. Аккаунт: войти / email + выйти.
2. Папка Drive и пароль: результат проверки канарейки под полем.
3. Синхронизация: параллельность 1–4, «Синхронизировать сейчас», время последнего прогона, список проблем.

**Первый запуск:** Notice «Mimir: настройте синхронизацию» с кнопкой, открывающей настройки.

### 4.7 Проверка платформы (шаг 0 реализации)

Команда «Mimir: diagnostics» в отдельной сборке проверяет на iPhone:
1. наличие и работу `adapter.appendBinary`;
2. потоковое чтение `fetch(getResourcePath)` файла 300 МБ без роста памяти;
3. `requestUrl` с заголовком `Range` и ответом 206;
4. время scrypt.

Доставка: `mise run obsidian:deploy-dir` собирает плагин в каталог, который пользователь кладёт в vault через «Файлы» или iCloud; альтернатива — пре-релиз + BRAT. Результаты пишутся в Ruling плана.

Если `appendBinary` нет:
- запись через `writeBinary` целиком;
- `minAppVersion` поднимается до версии с `appendBinary`;
- либо лимит размера на мобильных с проблемой «слишком большой для этой версии Obsidian».

Если поток через `getResourcePath` не работает — чтение `readBinary` с тем же лимитом.

## 5. Инварианты Mimir

- **Платформы:**
  - iOS, Android, desktop (Linux/Windows/macOS) — один бандл; на десктопе с демоном, обслуживающим vault, — режим «уступаю».
  - Docker-демон и web-ui — неприменимо: это не клиенты Obsidian.
  - Выбор папки: vault уже открыт в Obsidian, нативный проводник не нужен; папка Drive вводится текстом.
- **Дизайн:**
  - порты и адаптеры (`LocalVault` → `ObsidianLocalVault`, `SyncStateStore`, `SecretStore`/`TokenStorage`, Ktor `HttpClientEngine`);
  - декоратор `EncryptedRemoteStorage`; стратегия скачивания в `GoogleDriveStorage`; план → исполнение (`SyncPlanBuilder` → `DefaultSyncEngine`); UDF-контроллер.
  - Эталоны: `apps/browser-extension/.../Popup.kt`, `FileSystemLocalVault`, `JsonSyncStateStore`.
- **IO:**
  - на N файлов: 1+ вызов `getChanges` (или `listAll` ≈ N/1000 страниц); чтение только изменённых файлов, метаданные без чтения содержимого;
  - скачивание ⌈size/8 МиБ⌉ запросов, загрузка ⌈size/1 МиБ⌉;
  - память O(8 МиБ × parallelism); `Dispatchers.Default`, `parallelism = 2` (1–4 в настройках).
- **UX:**
  - состояния статуса (6) и настроек (3 блока с активацией по готовности); модалки входа (5 состояний) и конфликтов;
  - тексты в `PluginTexts` (en/ru);
  - проверка глазами на iPhone и desktop, светлая и тёмная тема.

## 6. План тестирования

TDD: сначала падающий тест.

**`libs/drive/commonTest` (`MockEngine`), скачивание кусками:**
- порядок и склейка кусков;
- последний кусок по `Content-Range`;
- пустой файл / `416`;
- 401 посреди файла → refresh и продолжение с того же смещения;
- отмена закрывает канал;
- `downloadChunkBytes = null` — текущее поведение без изменений.

**`apps/obsidian-plugin/jsTest`** (фейковый модуль `obsidian`: `FakeDataAdapter`, `FakeRequestUrl`, `FakeWorkspace`):
- `ObsidianLocalVault`: list/stat, исключение `configDir`, запись через временный файл и `rename`, удаление, события → `changes()`;
- `ObsidianHttpEngine`: метод, заголовки, тело, коды ошибок, отсутствие сети;
- `ObsidianSyncStateStore`: атомарность, повреждённый файл → пустой индекс с проблемой;
- `DeviceSecretStore`: токены и ключ вне файлов vault;
- `DaemonProbe`: совпадение и несовпадение пути, тайм-аут, не десктоп;
- `PluginSyncController`: каждый триггер, склейка, отмена при hidden, режим «уступаю». Только `advanceTimeBy` и `scope.cancel()`, без `advanceUntilIdle`;
- сквозной прогон: фейковый vault ↔ `InMemoryRemoteStorage` под `EncryptedRemoteStorage` — создание, изменение, удаление, конфликт, `SyncAbortedException`.

**`check-plugin.mjs`:** загрузка собранного `main.js` на фейковом obsidian в режимах desktop и mobile.

**Устройства:**
1. Шаг 0 — диагностика на iPhone (4.7).
2. Сквозной сценарий:
   - демон на десктопе пишет заметку и вложение 300 МБ → iPhone получает;
   - правка на iPhone → демон получает;
   - одновременная правка → копия конфликта.
3. Android — эмулятор через Device Hub с Obsidian из APK, если удастся поставить, иначе телефон пользователя.
