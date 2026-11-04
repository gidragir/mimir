# @mimir/drive

## 0.2.1

## 0.2.0

### Minor Changes

- 6c81529: Поддержка Android: модуль `:runtime` с общим движком синхронизации, вход через системный аккаунт Google (Google Identity Services) с fallback на браузер, фоновая синхронизация по триггеру WorkManager и MediaStore, статус-уведомления, адаптивная иконка и автоматический расчет versionCode
- 5173aaa: Модуль `:client` (контракт демона, DTO, HTTP-клиент с таймаутами и статусом связи), CORS только для расширения и Obsidian, потоковое чтение из Drive, `RcloneCipher.create` с отменяемой деривацией ключа, реактивный список конфликтов, плашка потери связи, цели касания 48 dp на Android, `AndroidFileObserverVaultWatcher`, WorkManager и `AndroidKeystoreSecretStore`
- 01915e1: Доработка десктопа: вход в Google одной кнопкой через системный браузер (PKCE, loopback) с Device Code как запасным способом, отдельные OAuth-клиенты Desktop и Device со вшитыми значениями в `daemon`, подробный человеческий прогресс синхронизации и состояние «нет сети», ошибка одного файла не прерывает синхронизацию, исправление отрисовки окна под niri (`_JAVA_AWT_WM_NONREPARENTING`), защита от удаления локальных файлов при пустом или отстающем списке файлов в Google Drive, vault не ходит по символьным ссылкам и не синхронизирует `node_modules` и вложенные `.git`, а файлы за ссылками не считаются удалёнными; удалённые в Google Drive файлы попадают в корзину, а не стираются
- b9b0d99: Google Drive storage adapter & OAuth flow: Ktor-based REST API v3 adapter, ChangeFeedStorage contract, DrivePathResolver, DriveUploader (simple & resumable) and OAuth 2.0 PKCE/Device Flow token client
- 70327d3: Синхронизация не скачивает файлы для сравнения, работает параллельно и потоками, соблюдает лимиты Google Drive
