# Mimir — доработка десктопа: вход через браузер, окно под niri, прогресс синхронизации

Дата: 2026-10-24  
Статус: согласовано в брейншторме, ждёт ревью пользователя  
Родительская спека: `docs/superpowers/specs/2026-10-23-mimir-design.md` (§ про OAuth и `drive.file`). При конфликте приоритет у родительской спеки.  
Дополняет: `docs/superpowers/specs/2026-10-24-mimir-desktop-design.md` (этап 5).  
Ветка: `feat/desktop-polish` от `main`.

---

## 1. Откуда задача

Пользователь впервые запустил десктоп (`mise run desktop`) с реальным Google-аккаунтом и сделал четыре замечания:

1. Вход через Device Code (открыть ссылку, ввести код) неудобен — нужна кнопка «Войти через Google».
2. На экране согласия Google показывается «Rclone-obsidian-home», а файлы, созданные ранее rclone, Mimir не видит.
3. Под niri (Wayland) окно растянуто тайлингом, но Compose рисует только левый верхний угол (~800×575), остальное чёрное.
4. Статус «Mimir: syncing» без подробностей: долгая первая синхронизация выглядит как зависание.

Цель итерации: конечный пользователь — **не программист** — на любом устройстве входит через Google одной кнопкой, видит нормальное окно и понимает, что происходит во время синхронизации.

## 2. Цели и не-цели

### 2.1 Цели

- Вход на десктопе через системный браузер с возвратом на loopback-адрес и PKCE (как и требует родительская спека). Device Code остаётся запасным путём и основным для Docker-демона.
- Клиент Mimir по умолчанию вшивается в релизные сборки; пользователю не нужен свой проект Google Cloud.
- Окно корректно рисуется под niri в тайловом и плавающем режиме; трей проверен на реальной панели.
- Подробный, человеческий прогресс синхронизации: всего, сделано, текущий файл, следующие, готовые, прошедшее время, оценка остатка, проблемные файлы; состояние «нет сети».
- Ошибка одного файла не останавливает синхронизацию остальных.
- Документация для владельца проекта Google Cloud.

### 2.2 Не-цели

- Вход на Android и в расширении браузера (свои типы клиентов — их этапы).
- Сохранение журнала синхронизации между перезапусками.
- Отмена идущей синхронизации, пауза, ограничение скорости.
- Потоковые события (SSE/WebSocket) в HTTP API — достаточно опроса.
- Отложенные мелочи этапа 5 (токен на `/vault` и `/unlock`, перерегистрация трея, задержка кэша конфликтов, пароль в открытом файле) — вне этой итерации.

### 2.3 Инварианты (не ломать)

- Строка статуса до подключения — ровно `Mimir: not connected`.
- `ui` не зависит от `daemon`; зависимости только вниз.
- `mise run test` и `mise run build` проходят без секретов и без Android SDK; configuration cache и build cache не ломаются.
- Существующие поля `/api/v1/status` не меняются; новые только добавляются.

---

## 3. Окно под niri и трей

### 3.1 Гипотеза

niri показывает X11-клиентов через xwayland-satellite — это не-reparenting оконный менеджер. Java AWT без `_JAVA_AWT_WM_NONREPARENTING=1` в таком окружении неправильно обрабатывает навязанный размер: содержимое остаётся в исходном размере, остальное не перерисовывается. Переменную нельзя выставить изнутри запущенной JVM — только в точке запуска.

### 3.2 Диагностика (первая задача плана)

1. `MIMIR_CONFIG_DIR=<временная папка> mise run desktop`; `niri msg windows` — подтвердить, что окно идёт через XWayland.
2. Повтор с `_JAVA_AWT_WM_NONREPARENTING=1`: тайловый режим, плавающий режим, ручной ресайз.
3. Если не помогло: `SKIKO_RENDER_API=SOFTWARE` (проблема рендера) и проверка наличия `WLToolkit` в JDK 27 (`-Dawt.toolkit.name=WLToolkit`).

Итог фиксируется в `.superpowers/sdd/<план>/progress.md` как `Ruling:`. Если причина окажется другой, исправление меняется соответственно, критерии приёмки (§3.4) остаются.

### 3.3 Исправление

- Рабочая переменная окружения выставляется во всех Linux-точках запуска: задача `desktop` в `mise.toml`, лаунчер Arch-пакета (`packaging/`, PKGBUILD) и `.desktop`-файл. Windows не затрагивается.
- `Main.kt`: `rememberWindowState(size = DpSize(960.dp, 680.dp))`, минимальный размер окна 720×520 (`window.minimumSize`).
- `MimirApp`: содержимое центрируется и ограничивается по ширине (~880 dp), чтобы на широком тайле поля не растягивались на весь экран.

### 3.4 Приёмка (ручная)

- Под niri в тайловом и плавающем режиме содержимое занимает всё окно и перерисовывается при ресайзе без чёрных областей.
- Окно не сжимается меньше минимального размера.
- Иконка трея (StatusNotifierItem) видна на панели noctalia/waybar; «Открыть», «Синхронизировать сейчас», «Выход» работают; подпись обновляется при смене статуса. Найденные дефекты трея исправляются в этой же ветке.

---

## 4. Вход через Google

### 4.1 Модель клиентов

Google жёстко разделяет типы OAuth-клиентов: клиент «Desktop app» поддерживает loopback-redirect, но не Device Code; клиент «TVs and Limited Input devices» — наоборот. Refresh-токен обновляется только тем клиентом, который его выдал. Все клиенты заводятся в **одном** проекте Google Cloud (право `drive.file` привязано к проекту, поэтому все клиенты видят одни и те же файлы).

В `drive`:

```kotlin
enum class GoogleClientKind { Desktop, Device }
data class GoogleClientCredentials(val kind: GoogleClientKind, val clientId: String, val clientSecret: String?)
```

### 4.2 Конфигурация и вшитые значения

- `DaemonConfig` получает две пары: `MIMIR_GOOGLE_DESKTOP_CLIENT_ID` / `MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET` и `MIMIR_GOOGLE_DEVICE_CLIENT_ID` / `MIMIR_GOOGLE_DEVICE_CLIENT_SECRET` (те же ключи в `config.json`). Старые `MIMIR_GOOGLE_CLIENT_ID` / `MIMIR_GOOGLE_CLIENT_SECRET` читаются как алиас Device-пары с предупреждением в лог.
- Значения по умолчанию вшиваются в BuildConfig **только модуля `daemon`** (JVM; его встраивает `app-desktop`). В `core` нельзя: его BuildConfig попадает в JS-бандлы плагина Obsidian и `web-ui`. Источник — переменные окружения сборки `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID`, `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_SECRET`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_SECRET`, читаемые через `providers.environmentVariable(...).orElse("")`. Локально пусто; `release.yml` передаёт их из GitHub Secrets.
- Приоритет: env / `config.json` → вшитое → отсутствует. Пустая строка = отсутствует.
- Секрет Desktop-клиента у устанавливаемого приложения не является секретом (Google это прямо оговаривает; защита — PKCE и согласие пользователя). Его вшивание допустимо и соответствует практике (rclone, Thunderbird). `DaemonConfig.toString()` продолжает редактировать секреты.
- `DaemonService.loginMethods: Set<LoginMethod>` (`Browser` — есть Desktop-клиент; `DeviceCode` — есть Device-клиент).

### 4.3 Токены

- `OAuthTokens` получает поле `clientKind: GoogleClientKind = GoogleClientKind.Device`. Значение по умолчанию сохраняет совместимость с уже сохранёнными токенами (они выданы TV-клиентом) — повторный вход после обновления не нужен.
- Refresh выполняется парой, соответствующей `clientKind`. Если такой пары нет в конфигурации — `RemoteAuthException` («Нужно войти заново»), а не падение.
- Исправление: `GoogleAuthClient.buildAuthorizationUrl` больше не добавляет `client_secret` в URL авторизации (секрет попадал в историю браузера).

### 4.4 Поток входа через браузер

**Чистая логика (`drive/commonMain`, класс `BrowserLoginFlow` или аналог):**
- генерация PKCE `code_verifier` (43–128 символов) и `code_challenge` (S256), случайного `state`;
- сборка URL авторизации с `redirect_uri = http://127.0.0.1:<port>/callback`;
- разбор параметров redirect: `code` + `state` → успех; `error=access_denied` → «Вход отменён»; несовпадение `state` → отказ;
- обмен кода на токены через существующий `exchangeAuthorizationCode`, токены помечаются `clientKind = Desktop`.

**Приёмник (`daemon`, JVM):**
- Ktor CIO-сервер на `127.0.0.1:0` (случайный свободный порт), единственный маршрут `GET /callback`;
- отвечает браузеру простой HTML-страницей: «Готово! Можно вернуться в Mimir» или «Вход отменён. Вернитесь в Mimir и попробуйте снова»;
- останавливается сразу после первого корректного запроса или по тайм-ауту 5 минут.

**`DaemonService`:**
- `suspend fun startBrowserLogin(): String` — возвращает URL авторизации; повторный вызов отменяет предыдущую попытку (закрывает её приёмник);
- `suspend fun pollBrowserLogin(): BrowserLoginPoll` — `Pending | Authorized(email) | Failed(reason)`, где `reason` — человеческий текст («Вход отменён», «Время ожидания истекло», «Не удалось связаться с Google»).
- При `Authorized` поведение такое же, как у успешного Device Code (сохранение токенов, подключение хранилища).

**HTTP API v1:**
- `GET /api/v1/auth/methods` → `{"methods": ["browser", "device_code"]}`;
- `POST /api/v1/auth/browser/start` → `{"authUrl": "..."}`; `409`, если способ недоступен;
- `GET /api/v1/auth/browser/poll` → `{"status": "pending" | "authorized" | "failed", "email"?, "error"?}`.

`HttpDaemonClient` реализует те же методы. Сценарий одинаково работает для встроенного демона, внешнего демона на той же машине и плагина Obsidian. Для Docker-демона браузер и демон на разных хостах — там используется Device Code (как и в родительской спеке).

### 4.5 Интерфейс (шаг 2 мастера)

- Если доступен `Browser`: крупная кнопка «Войти через Google». Нажатие открывает браузер (`Desktop.browse`, при неудаче — `xdg-open`), под кнопкой «Ждём подтверждения в браузере…» и «Отмена». При неудачном открытии браузера показывается ссылка для копирования.
- Если доступен `DeviceCode`: ниже мелкая ссылка «Войти с помощью кода на другом устройстве», ведущая на существующий экран с кодом.
- Если доступен только `DeviceCode` — сразу экран с кодом (как сейчас). Если не доступно ничего — сообщение «Вход в Google не настроен в этой сборке» и ссылка на README.

`MimirUiModel` получает `loginMethods`, `startBrowserLogin()`, `pollBrowserLogin()`; `EngineMimirUiModel` — `unsupported()`, как для Device Code.

---

## 5. Прогресс синхронизации

### 5.1 Модель (`core`, пакет `dev.mimir.core`)

```kotlin
enum class SyncPhase { Scanning, Comparing, Applying, Finished }
enum class SyncItemAction { Upload, Download, DeleteLocal, DeleteRemote, Conflict }
enum class SyncProblemKind { ReadFailed, WriteFailed, DecryptFailed, Unknown }

data class SyncItem(val path: String, val action: SyncItemAction)
data class CompletedItem(val path: String, val action: SyncItemAction, val at: Instant)
data class SyncProblem(val path: String, val action: SyncItemAction, val kind: SyncProblemKind, val detail: String)

data class SyncProgress(
    val phase: SyncPhase,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val scanned: Int,              // для Scanning/Comparing, где итог ещё неизвестен
    val total: Int,                // число действий плана; 0 до Applying
    val done: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val current: SyncItem?,
    val upcoming: List<SyncItem>,  // следующие до 5
    val completed: List<CompletedItem>, // последние до 200
    val problems: List<SyncProblem>,    // все проблемы текущего прогона
)
```

- `etaSeconds(now, applyingStartedAt)` — функция, а не хранимое поле: `elapsedApplying × (bytesTotal − bytesDone) / bytesDone`. Удаление весит 4 КБ. Возвращает `null`, пока прошло меньше 2 секунд в `Applying` или не готов ни один файл.
- Вес действия: `Upload` — размер локального файла; `Download` — размер удалённого; `Conflict` — сумма; удаления — 4 КБ.
- `SyncEngine` получает `val progress: StateFlow<SyncProgress?>` (`null` — синхронизаций в этом процессе не было). После завершения последний снимок сохраняется с фазой `Finished` и служит сводкой «Последняя синхронизация».

### 5.2 Статус

- `SyncStatus` получает `data object Offline` с меткой `Mimir: waiting for network`. Остальные метки не меняются; `Mimir: not connected` — неизменна.
- Итог прогона: без проблем — `Idle`; с проблемами файлов — тоже `Idle` (сводка в `progress`); нет сети — `Offline`; доступ отозван — `Error`.
- Метка трея (не `label()`, отдельная функция для трея): во время `Applying` — `Mimir: syncing 12/87`; после прогона с проблемами — `Mimir: up to date (2 problems)`.

### 5.3 Движок (`DefaultSyncEngine`)

- Фазы: `Scanning` (локальный список и обход Drive, `scanned` растёт), `Comparing` (построение плана, хэши спорных файлов), `Applying` (действия плана), `Finished`.
- Каждое действие выполняется в собственном `try`. Ошибка файла → запись в `problems`, индекс для этого файла не меняется (файл повторится в следующем прогоне), движок переходит к следующему действию.
- Прогон прерывают только общие ошибки:
  - `RemoteUnavailableException` (core) — нет сети / Drive недоступен → `Offline`;
  - `RemoteAuthException` (core) — доступ отозван или нужен повторный вход → `Error`;
  - ошибка проверки пароля (canary) → `Error`;
  - `CancellationException` — пробрасывается.
- Адаптер `drive` переводит свои сетевые исключения (`IOException`, тайм-ауты, 5xx после ретраев) в `RemoteUnavailableException`, а `AuthRevokedException` / `DriveAuthException(401/403 после refresh)` — в `RemoteAuthException`.
- Классификация файловых ошибок в `SyncProblemKind`: ошибки `LocalVault` при чтении → `ReadFailed`, при записи → `WriteFailed`, ошибки расшифровки содержимого → `DecryptFailed`, остальное → `Unknown`. `detail` — исходное сообщение исключения.
- Снимок публикуется в `MutableStateFlow` на каждом шаге; частоту ограничивают потребители (StateFlow сбрасывает промежуточные значения).

### 5.4 Демон и HTTP API

- `DaemonService` получает `progress: StateFlow<SyncProgress?>`, проксируя движок.
- `SyncScheduler`: при `Offline` повторяет попытку по своему обычному тику; сообщение `lastError` больше не используется для отображения сети.
- `GET /api/v1/status` получает новое поле `progress` (объект с полями `SyncProgress` + вычисленное `etaSeconds`, время в ISO-8601) и новый код `status = "offline"`. Остальные поля без изменений.

### 5.5 Интерфейс (макет A, выбран в брейншторме)

Одна колонка. Сверху вниз:

1. Заголовок фазы («Ищу изменения…», «Синхронизация…», «✓ Всё синхронизировано», «Нет подключения к интернету», «Нужно снова войти в Google»).
2. Полоса прогресса (неопределённая в `Scanning`/`Comparing`), строка «12 из 87 файлов» и «прошло 1 мин 20 с · осталось ≈ 3 мин»; в `Scanning` — «Проверено 340 файлов · прошло 12 с».
3. Блок проблем (если есть) — **над** остальными: «⚠ 1 файл не удалось синхронизировать», для каждого — путь, человеческая фраза, «Mimir попробует ещё раз при следующей синхронизации», раскрывающееся «Подробнее» с `detail`.
4. «Сейчас»: «⬆ Отправляю в Google Drive: daily/2026-10-24.md» / «⬇ Загружаю: …» / «Удаляю …» / «Сохраняю обе версии: …».
5. «Далее · и ещё N» — до 5 строк.
6. «Готово (N)» — свёрнуто по умолчанию, раскрывается списком последних 200.

После завершения: «✓ Всё синхронизировано — сегодня в 14:32 · 87 файлов за 4 мин 05 с», ссылка на проблемы, кнопка «Синхронизировать сейчас». В `Offline`: «Mimir продолжит, когда сеть появится. Изменения на этом компьютере сохранены». В `Error` из-за доступа — кнопка «Войти через Google».

Все пользовательские тексты собраны в одном файле `ui` (`SyncTexts.kt`): фразы для фаз, действий и `SyncProblemKind` (например, `ReadFailed` → «не удалось прочитать файл — возможно, он открыт в другой программе»; `WriteFailed` → «не удалось сохранить файл — проверьте свободное место и права на папку»; `DecryptFailed` → «не удалось расшифровать — возможно, файл зашифрован другим паролем»). Технические подробности — только под «Подробнее».

`MimirUiModel` получает `progress: StateFlow<SyncProgress?>`; реализуют `DaemonServiceUiModel`, `FakeMimirUiModel`, `EngineMimirUiModel` (из `engine.progress`). `HttpDaemonClient` опрашивает `/status` раз в секунду во время синхронизации, в покое — с прежней частотой.

---

## 6. Документация

- README, раздел «Google Cloud» (для владельца проекта): переименование экрана согласия в «Mimir» (Branding → App name); создание двух OAuth-клиентов (Desktop app и TVs and Limited Input devices) в одном проекте; перевод проекта в «In production» и проверка бренда (`drive.file` — нечувствительное право, security-аудит не нужен); почему в статусе «Testing» вход доступен только тестовым пользователям и refresh-токены истекают через 7 дней; почему не видны файлы, созданные rclone через другой client ID (и как это обойти — rclone с client ID того же проекта); передача значений в GitHub Secrets для релизов.
- README, раздел «Свой OAuth-клиент» для продвинутых пользователей: переменные `MIMIR_GOOGLE_DESKTOP_*` / `MIMIR_GOOGLE_DEVICE_*`.
- `AGENTS.md`: новые переменные; вшитые значения только в `daemon`; `_JAVA_AWT_WM_NONREPARENTING` в Linux-лаунчерах; прогресс и `Offline` в описании `SyncStatus`.
- В спеку этапа 5 — пометка «дополнена `2026-10-24-mimir-desktop-polish-design.md`» у пунктов про Device Code и статус.
- Changeset `minor` для всех модулей группы `fixed`.

---

## 7. Тестирование

TDD: сначала падающий тест.

| Где | Что проверяем |
|---|---|
| `drive/commonTest` | PKCE (длина verifier, S256 challenge по эталонному вектору RFC 7636), сборка URL без `client_secret`, разбор redirect (успех, `access_denied`, неверный `state`), refresh выбирает пару по `clientKind`, десериализация старого файла токенов → `Device`; перевод сетевых ошибок в `RemoteUnavailableException` / `RemoteAuthException` |
| `core/commonTest` | фазы и счётчики `SyncProgress`; ошибка одного файла не останавливает остальные, попадает в `problems`, файл повторяется в следующем прогоне; `RemoteUnavailableException` → `Offline`, `RemoteAuthException` → `Error`; ETA с тестовыми часами; `upcoming` ≤ 5, `completed` ≤ 200 |
| `daemon/test` | настоящий loopback-приёмник + `MockEngine` для token endpoint: успех, неверный `state`, отказ пользователя, тайм-аут, повторный `start` отменяет предыдущий; `DaemonConfig` (новые переменные, алиас, приоритет env → вшитое); `RoutesTest`: `/auth/methods`, `/auth/browser/*`, поле `progress`, код `offline` |
| `app-desktop/test` | `HttpDaemonClientTest`: разбор `progress`, методы входа через браузер |
| `ui/desktopTest` | `SetupWizardScreenTest`: кнопка/ссылка в зависимости от `loginMethods`, ожидание и отмена; `StatusScreenTest`: шесть состояний макета A через `FakeMimirUiModel` |

Ручная приёмка на Arch/niri: §3.4; вход кнопкой с реальным Desktop-клиентом; первая синхронизация с видимым прогрессом; выключение сети → «Нет подключения к интернету» и продолжение после включения; файл без прав на чтение попадает в проблемы, остальные синхронизируются.

---

## 8. Порядок работ

Каждый шаг — законченный, проверяемый кусок в ветке `feat/desktop-polish`:

1. **Окно** (малый): диагностика §3.2 → исправление §3.3 → ручная приёмка §3.4.
2. **Документация Google Cloud** (малый): §6, кроме пунктов, зависящих от следующих шагов.
3. **Вход через браузер** (средний): `drive` (клиенты, токены, логика PKCE, исправление URL) → `daemon` (конфиг, вшитые значения, приёмник, `DaemonService`, API) → `app-desktop` (`HttpDaemonClient`, открытие браузера) → `ui` (мастер) → `release.yml` (секреты сборки).
4. **Прогресс** (крупный): `core` (модель, исключения, движок) → `drive` (перевод исключений) → `daemon` (прокси, `SyncScheduler`, API) → `app-desktop` (`HttpDaemonClient`, трей) → `ui` (`StatusScreen`, `SyncTexts`).
5. Финал: `AGENTS.md`, пометка в спеке этапа 5, changeset, `mise run test` и `mise run build` без секретов и Android SDK.
