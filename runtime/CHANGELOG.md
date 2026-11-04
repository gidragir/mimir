# @mimir/runtime

## 0.2.1

### Patch Changes

- 735e932: fix(desktop): register mimir:// URI scheme on Windows startup and enable native FFM access for window foreground activation

## 0.2.0

### Minor Changes

- 6c81529: Поддержка Android: модуль `:runtime` с общим движком синхронизации, вход через системный аккаунт Google (Google Identity Services) с fallback на браузер, фоновая синхронизация по триггеру WorkManager и MediaStore, статус-уведомления, адаптивная иконка и автоматический расчет versionCode
- 0015ea5: Кроссплатформенная система автообновлений: клиент GitHub Releases API с парсингом SemVer и сопоставлением платформенных ассетов (MSI, APK, zip, standalone main.js/manifest.json), конечный автомат `UpdateManager` с поддержкой ETag-кэширования (HTTP 304), фоновый планировщик `DailyUpdateChecker` и `UpdateScheduler`, HTTP API демона (`/api/v1/update`), платформенные аппликаторы (`WindowsMsiApplier`, `AndroidApkApplier` с FileProvider, `ObsidianInPlaceApplier`), карточка `AboutUpdateCard` в настройках UI и уведомления в расширении браузера.
