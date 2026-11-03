---
"@mimir/core": minor
"@mimir/runtime": minor
"@mimir/daemon": minor
"@mimir/ui": minor
"@mimir/app-desktop": minor
"@mimir/app-android": minor
"@mimir/obsidian-plugin": minor
"@mimir/browser-extension": minor
---

Кроссплатформенная система автообновлений: клиент GitHub Releases API с парсингом SemVer и сопоставлением платформенных ассетов (MSI, APK, zip, standalone main.js/manifest.json), конечный автомат `UpdateManager` с поддержкой ETag-кэширования (HTTP 304), фоновый планировщик `DailyUpdateChecker` и `UpdateScheduler`, HTTP API демона (`/api/v1/update`), платформенные аппликаторы (`WindowsMsiApplier`, `AndroidApkApplier` с FileProvider, `ObsidianInPlaceApplier`), карточка `AboutUpdateCard` в настройках UI и уведомления в расширении браузера.
