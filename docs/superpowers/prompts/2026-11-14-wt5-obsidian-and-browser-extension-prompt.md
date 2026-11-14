# Промпт исполнителя — WT5: Obsidian Plugin и Browser Extension (после слияния WT1 + WT2)

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:executing-plans`, `superpowers:test-driven-development`, `mimir-sync-io`, `mimir-ui-ux`, `mimir-cross-platform`, `mimir-build-verify` и `superpowers:verification-before-completion`.
Для исследования архитектуры и API используй `rtk context-init pack <subsystem_id> --compress` (`apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian`, `apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian_ui`, `apps_browser-extension_jsmain_kotlin_dev_mimir_extension`, `apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui`). Не вычитывай файлы директории целиком.

Задача: выполнить план `docs/superpowers/plans/2026-11-14-wt5-obsidian-and-browser-extension.md` (спеки: `docs/superpowers/specs/2026-11-09-obsidian-plugin-sync-design.md`, `docs/superpowers/specs/2026-11-01-mimir-browser-extension-design.md`). В `main` уже влиты WT1 и WT2 (`CanaryVerifier.verifyOrInit`, `CanaryVerifier.CANARY_BODY`, `TestCiphers.forPassword`, `TestClock`, `FakeSyncEngine`, `FakeUpdateHttpClient`, `FakeUpdateChannel` доступны в `:libs:core`).

Порядок работы:
1. Если воркспейс ещё не изолирован через Antigravity `Workspace: "share"`, создай и перейди в `.worktrees/wt-js-clients` на ветке `refactor/obsidian-and-extension`; иначе создай ветку `rtk git checkout -b refactor/obsidian-and-extension`.
2. Выполни задачи Task 1 — Task 2 по TDD строго в границах `apps/obsidian-plugin/src/**` и `apps/browser-extension/src/**` (не трогая `libs/**` и `*.gradle.kts`), отмечая прогресс в `.superpowers/sdd/2026-11-14-wt5-obsidian-and-browser-extension/progress.md`.
3. Все вызовы `./gradlew` запускай с `--max-workers=4`. Перед завершением прогони `rtk ./gradlew :apps:obsidian-plugin:checkPlugin :apps:browser-extension:checkExtension --max-workers=4`, проверь `rtk context-init check --compact` и останови демон `rtk ./gradlew --stop`.
4. В отчёте приведи вывод верификации, хэши коммитов и раздел «Инварианты Mimir» (Платформы, Дизайн, IO, UX).
```
