# Промпт исполнителя — WT2: Криптография, локальная ФС, SyncIndex и тестовые фейки (:libs:core)

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:using-git-worktrees`, `superpowers:executing-plans`, `superpowers:test-driven-development`, `mimir-sync-io`, `mimir-design-patterns`, `mimir-build-verify` и `superpowers:verification-before-completion`.
Для исследования архитектуры и API используй `rtk context-init pack <subsystem_id> --compress` (`libs_core`, `libs_core_commonmain_kotlin_dev_mimir_core`, `libs_core_commonmain_kotlin_dev_mimir_core_crypto`, `libs_core_commonmain_kotlin_dev_mimir_core_sync`). Не вычитывай файлы директории целиком.

Задача: выполнить план `docs/superpowers/plans/2026-11-14-wt2-core-crypto-and-sync-io.md` (спеки: `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md`, `docs/superpowers/specs/2026-10-24-mimir-sync-io-design.md`).

Порядок работы:
1. Создай и перейди в изолированный worktree `.worktrees/wt-core-crypto-sync` на ветке `perf/core-crypto-and-sync` (`rtk git worktree add .worktrees/wt-core-crypto-sync -b perf/core-crypto-and-sync`).
2. Выполни задачи Task 1 — Task 4 по TDD строго в границах `libs/core/**`, отмечая прогресс в `.superpowers/sdd/2026-11-14-wt2-core-crypto-and-sync-io/progress.md`.
3. Перед завершением прогони `rtk ./gradlew :libs:core:allTests` и проверь дрейф `rtk context-init check --compact` (при дрейфе выполни `rtk context-init update . --compact` и закоммить обновлённую базу знаний).
4. В отчёте приведи вывод верификации и раздел «Инварианты Mimir» (Платформы, Дизайн, IO, UX).
```
