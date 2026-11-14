# Промпт исполнителя — WT1: CI/CD, Gradle и инфраструктура тестов

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:using-git-worktrees`, `superpowers:executing-plans`, `mimir-ci-scope`, `mimir-build-verify` и `superpowers:verification-before-completion`.
Для исследования архитектуры и API используй `rtk context-init pack <subsystem_id> --compress` (`build-logic`, `packaging`, `scripts_win-vm`, `scripts_win-vm_tests`). Не вычитывай файлы директории целиком.

Задача: выполнить план `docs/superpowers/plans/2026-11-14-wt1-ci-build-and-test-infra.md` (спеки: `docs/superpowers/specs/2026-11-06-ci-build-test-improvements-design.md`, `docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md`).

Порядок работы:
1. Создай и перейди в изолированный worktree `.worktrees/wt-ci-build` на ветке `refactor/ci-and-build` (`rtk git worktree add .worktrees/wt-ci-build -b refactor/ci-and-build`).
2. Выполни задачи Task 1 — Task 4 строго в границах файлов плана (не трогая `libs/*/src` и `apps/*/src`), отмечая прогресс в `.superpowers/sdd/2026-11-14-wt1-ci-build-and-test-infra/progress.md`.
3. Перед завершением прогони `rtk mise run ci:fast`, `rtk ./gradlew :libs:core:jsTest :apps:obsidian-plugin:checkPlugin :apps:browser-extension:checkExtension` и проверь дрейф `rtk context-init check --compact` (при дрейфе выполни `rtk context-init update . --compact`).
4. В отчёте приведи вывод верификации и раздел «Инварианты Mimir» (Платформы, Дизайн, IO, UX).
```
