# Промпт исполнителя — WT4: UI, Desktop, Android и Web-UI (после слияния WT1 + WT2)

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:executing-plans`, `superpowers:test-driven-development`, `mimir-ui-ux`, `mimir-cross-platform`, `mimir-design-patterns`, `mimir-build-verify` и `superpowers:verification-before-completion`.
Для исследования архитектуры и API используй `rtk context-init pack <subsystem_id> --compress` (`libs_ui_commonmain_kotlin_dev_mimir_ui`, `libs_ui_commonmain_kotlin_dev_mimir_ui_screens`, `apps_desktop_main_kotlin_dev_mimir_desktop`, `apps_desktop_main_kotlin_dev_mimir_desktop_tray`, `apps_android`, `apps_web-ui`). Не вычитывай файлы директории целиком.

Задача: выполнить план `docs/superpowers/plans/2026-11-14-wt4-ui-desktop-android-and-web.md` (спеки: `docs/superpowers/specs/2026-11-02-unified-ui-components-design.md`, `docs/superpowers/specs/2026-11-03-web-ui-docker-design.md`, `docs/superpowers/specs/2026-10-27-mimir-android-design.md`). В `main` уже влиты WT1 и WT2 (`TestClock`, `FakeUpdateHttpClient` доступны в `dev.mimir.core.testing`).

Порядок работы:
1. Если воркспейс ещё не изолирован через Antigravity `Workspace: "share"`, создай и перейди в `.worktrees/wt-ui-desktop-android-web` на ветке `refactor/ui-desktop-android-web`; иначе создай ветку `rtk git checkout -b refactor/ui-desktop-android-web`.
2. Выполни задачи Task 1 — Task 3 по TDD строго в границах `libs/ui/src/**`, `apps/desktop/src/**`, `apps/android/src/**`, `apps/web-ui/**`, отмечая прогресс в `.superpowers/sdd/2026-11-14-wt4-ui-desktop-android-and-web/progress.md`.
3. Все вызовы `./gradlew` запускай с `--max-workers=4`. Перед завершением прогони `rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs :apps:desktop:test :apps:android:testDebugUnitTest :apps:web-ui:wasmJsBrowserDistribution --max-workers=4`, проверь `rtk context-init check --compact` и останови демон `rtk ./gradlew --stop`.
4. В отчёте приведи вывод верификации, хэши коммитов и раздел «Инварианты Mimir» (Платформы, Дизайн, IO, UX).
```
