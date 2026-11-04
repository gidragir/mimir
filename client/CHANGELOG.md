# @mimir/client

## 0.2.1

## 0.2.0

### Minor Changes

- 6c81529: Поддержка Android: модуль `:runtime` с общим движком синхронизации, вход через системный аккаунт Google (Google Identity Services) с fallback на браузер, фоновая синхронизация по триггеру WorkManager и MediaStore, статус-уведомления, адаптивная иконка и автоматический расчет versionCode
- 5173aaa: Модуль `:client` (контракт демона, DTO, HTTP-клиент с таймаутами и статусом связи), CORS только для расширения и Obsidian, потоковое чтение из Drive, `RcloneCipher.create` с отменяемой деривацией ключа, реактивный список конфликтов, плашка потери связи, цели касания 48 dp на Android, `AndroidFileObserverVaultWatcher`, WorkManager и `AndroidKeystoreSecretStore`
