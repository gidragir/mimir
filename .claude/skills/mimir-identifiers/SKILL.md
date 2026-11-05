---
name: mimir-identifiers
description: Use when a Mimir name, version, id, port, UUID or other long-lived identifier must be added, changed or used in code, manifests, packaging or CI — it lives only in gradle.properties and is propagated, never duplicated.
---

# Долгоживущие идентификаторы

Единственный источник правды — `gradle.properties`:

`mimir.appName`, `mimir.version`, `mimir.group`, `mimir.appId`, `mimir.obsidianPluginId`, `mimir.pacmanName`, `mimir.dockerImage`, `mimir.msiUpgradeUuid`, `mimir.apiDefaultPort`.

## Как использовать

| Куда | Как |
|---|---|
| Kotlin-код | `BuildConfig` модуля `libs/core` (`APP_NAME`, `VERSION`, `APP_ID`, `OBSIDIAN_PLUGIN_ID`, `API_DEFAULT_PORT`). Новое поле — в `buildConfig {}` в `libs/core/build.gradle.kts` через `providers.gradleProperty(...)` |
| Gradle-скрипты и convention-плагины | `providers.gradleProperty("mimir.…")` |
| Манифесты/шаблоны (Obsidian `manifest.template.json`, расширение, Android `strings.xml`) | подстановка при сборке (`processResources`/`resValue`/задача сборки), шаблон без литералов |
| compose desktop (`nativeDistributions`) | `packageName`/`packageVersion`/`upgradeUuid` из properties |
| PKGBUILD / CI | `pkgname`/`pkgver` подставляются скриптом `.github/scripts/build-arch.sh` и `sed`; теги и имя Docker-образа — из properties |

## Правила

- Новый идентификатор: сначала строка в `gradle.properties`, потом использование. Не вводить второй источник.
- Никаких литералов `"Mimir"`, `"0.1.0"`, `47821`, `"dev.mimir.app"` в коде/манифестах — тесты тоже берут значения из `BuildConfig`, где это возможно (заведомо константы протокола вроде `Mimir: not connected` — часть спеки и остаются строкой).
- `mimir.msiUpgradeUuid` не менять после первого релиза MSI — иначе обновления перестанут находить старую установку.
- Секреты (OAuth, keystore) — не здесь: только `mise.local.toml` и GitHub Secrets.
