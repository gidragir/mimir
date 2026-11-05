---
name: mimir-ci-scope
description: Use when a Mimir task is about CI/CD, GitHub Actions workflows, release pipeline, build/test speed, flaky CI, runner matrix, mise tasks or Gradle build wiring — "улучши CI", "ускорь сборку", "почему упал CI", ревью/спека/план CI, Windows/macOS-раннер, MSI/APK/Docker/Arch-пакеты.
---

# CI-задачи Mimir: закрытая поверхность

## Суть

CI Mimir живёт в ~15 файлах ниже. Код приложения (`libs/*/src`, `apps/*/src`) — не вход CI-задачи. Ответ готов, когда каждое утверждение опирается на `файл:строка` из этой таблицы.

## CI-поверхность

| Что | Файл |
|---|---|
| PR/push-матрица: `detect-changes`, `paths-filter`, `os_matrix`, `package_msi`, `build` | `.github/workflows/ci.yml` |
| Релиз: gate `verify`, msi/apk/zip, arch, docker | `.github/workflows/release.yml`, `release-please.yml`, `.github/release-please-config.json` |
| Таски и тулчейн | `mise.toml` |
| `ciJvmTests`, `mustRunAfter` для `kotlinWasmNpmInstall`, dependency-analysis | корневой `build.gradle.kts` |
| JVM-таргет, KMP/Karma (`useConfigDirectory`), Android | `build-logic/src/main/kotlin/mimir.*.gradle.kts`, `gradle/karma.config.d/` |
| Флаги сборки: `mimir.bundleWebUi` | `apps/daemon/build.gradle.kts` |
| `mimir.screenshots` | `libs/ui/build.gradle.kts` |
| `packageMsi`, jvmArgs | `apps/desktop/build.gradle.kts` |
| `packageReleaseApk`, подпись | `apps/android/build.gradle.kts` |
| Память/кэш Gradle | `gradle.properties` |
| Пакеты | `packaging/arch/build-arch.sh`, `packaging/docker/Dockerfile`, `.dockerignore` |

Правила CI из `AGENTS.md` уже в контексте: это раздел «Выборочный запуск CI…» и соседние пункты. Перечитывать его не нужно.

## Порядок работы

1. Сопоставить вопрос со строками таблицы и выписать 2–5 файлов.
2. В каждом файле найти ключ (имя job, step, таска, флага) через `rtk rg -n '<ключ>' <файл>`, затем прочитать диапазон `sed -n A,Bp`. Целиком читается только `ci.yml`, когда меняется он сам.
3. Факты прогонов брать так:
   - длительности: `gh run view <id> --json jobs`;
   - причина падения: `gh run view <id> --log-failed`.
   Хватает одного последнего релевантного прогона.
4. Граф Gradle проверять для конкретной задачи: `./gradlew <task> --dry-run`.
5. Остановиться, когда у каждого пункта ответа есть `файл:строка`. Если чего-то нет в таблице, это либо гипотеза («проверить: …»), либо повод для шага 6.
6. Выход за таблицу — только по наблюдаемому триггеру:
   - Лог/стектрейс указывает на `X.kt:N` → читать диапазон вокруг `N` в `X.kt`.
   - Нужен список платформозависимых тестов → `rtk rg -l '<API>' --glob '*Test.kt'`. Получится список путей, сами тесты не читать.
   - Изменение затрагивает новый модуль → `settings.gradle.kts` и его `build.gradle.kts`.

## «Инварианты Mimir» для CI-задачи

Для спеки, плана и отчёта используется этот шаблон. Код для него искать не нужно.

- **Платформы:** какие раннеры и цели затронуты (ubuntu, windows, macos, Arch, Docker, Android) и что меняется на каждом.
- **Дизайн:** конвенция `build-logic`, mise-таск или composite action, куда встраивается изменение. Если изменение только в YAML — «неприменимо: не код приложения».
- **IO:** «неприменимо: Drive и vault не затрагиваются». Вместо этого — стоимость CI: минуты раннеров (Windows ×2, macOS ×10), число вызовов Gradle, кэш.
- **UX:** «неприменимо», либо изменённый вывод и сообщения CI.

## Делегирование

Ревьюер или субагент получает дифф, файлы из шага 1 и этот скилл. Обзорную карту проекта (`context_pack all`, `git ls-files | xargs wc`) ему не передавать: для CI из context-init нужны максимум L1 `build-logic` и `packaging`.

## Проверка

`mise run ci:fast` (токены + `actionlint` + `ciJvmTests`). Изменения workflow проверяются `actionlint`. Релиз проверяется без публикации: `gh workflow run release.yml --ref <ветка> -f tag=v<версия> -f dry_run=true`.
