# Промпт исполнителя — WT3: Google Drive, Runtime, Client и Daemon (после слияния WT1 + WT2)

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:executing-plans`, `superpowers:test-driven-development`, `mimir-sync-io`, `mimir-design-patterns`, `mimir-build-verify` и `superpowers:verification-before-completion`.
Для исследования архитектуры и API используй `rtk context-init pack <subsystem_id> --compress` (`libs_drive`, `libs_runtime_commonmain_kotlin_dev_mimir_runtime`, `libs_runtime_commonmain_kotlin_dev_mimir_runtime_update`, `libs_client`, `apps_daemon`). Не вычитывай файлы директории целиком.

Задача: выполнить план `docs/superpowers/plans/2026-11-14-wt3-drive-runtime-and-daemon.md` (спеки: `docs/superpowers/specs/2026-10-23-mimir-drive-design.md`, `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md`). В `main` уже влиты WT1 и WT2 (`CanaryVerifier.verifyOrInit`, `TestCiphers.forPassword`, `TestClock`, `FakeSyncEngine`, `CountingLocalVault`, `FakeUpdate*` доступны в `:libs:core`).

Порядок работы:
1. Если воркспейс ещё не изолирован через Antigravity `Workspace: "share"`, создай и перейди в `.worktrees/wt-drive-runtime-daemon` на ветке `refactor/drive-runtime-daemon`; иначе создай ветку `rtk git checkout -b refactor/drive-runtime-daemon`.
2. Выполни задачи Task 1 — Task 4 по TDD строго в границах `libs/drive/src/**`, `libs/runtime/src/**`, `libs/client/src/**`, `apps/daemon/src/**` (не трогая другие модули и `*.gradle.kts`), отмечая прогресс в `.superpowers/sdd/2026-11-14-wt3-drive-runtime-and-daemon/progress.md`.
3. Все вызовы `./gradlew` запускай с `--max-workers=4`. Перед завершением прогони `rtk ./gradlew :libs:drive:allTests :libs:client:allTests :libs:runtime:jvmTest :apps:daemon:test --max-workers=4`, проверь `rtk context-init check --compact` и останови демон `rtk ./gradlew --stop`.
4. В отчёте приведи вывод верификации, хэши коммитов и раздел «Инварианты Mimir» (Платформы, Дизайн, IO, UX).
```
