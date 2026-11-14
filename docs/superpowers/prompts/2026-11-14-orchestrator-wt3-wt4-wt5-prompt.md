# Мастер-промпт для Argon-оркестратора (параллельное выполнение WT3 + WT4 + WT5 в Antigravity)

> Запускается после того, как **WT1** (`2026-11-14-wt1-ci-build-and-test-infra.md`), **WT2** (`2026-11-14-wt2-core-crypto-and-sync-io.md`) и сами файлы `docs/superpowers/plans/2026-11-14-wt*.md` влиты и закоммичены в `main`.

## Вариант А (Рекомендуемый, экономный по квоте): параллельные `invoke_subagent` с `Workspace: "share"`

```text
Активируй скиллы `superpowers:using-superpowers`, `superpowers:dispatching-parallel-agents`, `mimir-build-verify` и `superpowers:verification-before-completion`.

В `main` уже влиты WT1 (инфраструктура CI/Gradle/тестов) и WT2 (`:libs:core` криптография, ФС, SyncIndex и общие тестовые фейки в `dev.mimir.core.testing`).
Твоя задача как оркестратора — параллельно выполнить 3 непересекающихся плана в изолированных рабочих копиях Antigravity и свести их в `main`:

1. Проверь через `rtk git status`, что рабочее дерево `main` чистое и планы `docs/superpowers/plans/2026-11-14-wt{3,4,5}-*.md` закоммичены в `HEAD`.
2. В одном ходе запусти 3 параллельных субагента через `invoke_subagent` (`TypeName: "self"`, `Model: "inherit"`, `Workspace: "share"`), передав им содержимое соответствующих промптов:
   - Subagent 1 (`Role: "WT3 Drive Runtime Daemon Implementer"`): промпт из `docs/superpowers/prompts/2026-11-14-wt3-drive-runtime-and-daemon-prompt.md` (ветка `refactor/drive-runtime-daemon`, строго `libs/drive/src/**`, `libs/runtime/src/**`, `libs/client/src/**`, `apps/daemon/src/**`).
   - Subagent 2 (`Role: "WT4 UI Desktop Android Web Implementer"`): промпт из `docs/superpowers/prompts/2026-11-14-wt4-ui-desktop-android-and-web-prompt.md` (ветка `refactor/ui-desktop-android-web`, строго `libs/ui/src/**`, `apps/desktop/src/**`, `apps/android/src/**`, `apps/web-ui/**`).
   - Subagent 3 (`Role: "WT5 JS Clients Implementer"`): промпт из `docs/superpowers/prompts/2026-11-14-wt5-obsidian-and-browser-extension-prompt.md` (ветка `refactor/obsidian-and-extension`, строго `apps/obsidian-plugin/src/**`, `apps/browser-extension/src/**`).
3. Дождись завершения всех 3 субагентов (не полль в цикле).
4. Последовательно влей ветки `refactor/drive-runtime-daemon`, `refactor/ui-desktop-android-web` и `refactor/obsidian-and-extension` в `main`.
5. Обнови базу знаний `rtk context-init update . --compact`, при наличии изменений закоммить их через `rtk oco -y`, и выполни финальную верификацию `rtk mise run ci:fast && rtk ./gradlew check`.
6. Выведи итоговый отчёт со списком влитых коммитов и разделом «Инварианты Mimir».
```

---

## Вариант Б (Через `/teamwork` / нативный `swarm` с верификатором)

```text
/teamwork Запусти рой (swarm) для параллельной реализации трёх изолированных планов после слияния WT1 и WT2:
1. Воркер `drive-runtime` (`workspace: "share"`): выполняет `docs/superpowers/prompts/2026-11-14-wt3-drive-runtime-and-daemon-prompt.md` на ветке `refactor/drive-runtime-daemon`.
2. Воркер `ui-clients` (`workspace: "share"`): выполняет `docs/superpowers/prompts/2026-11-14-wt4-ui-desktop-android-and-web-prompt.md` на ветке `refactor/ui-desktop-android-web`.
3. Воркер `js-clients` (`workspace: "share"`): выполняет `docs/superpowers/prompts/2026-11-14-wt5-obsidian-and-browser-extension-prompt.md` на ветке `refactor/obsidian-and-extension`.
4. Воркер `verifier` (`workspace: "inherit"`): проверяет диффы каждой из трёх веток на соблюдение границ файлов и инвариантов Mimir перед тем, как координатор сольёт их в `main` и запустит финальный `rtk mise run ci:fast && rtk ./gradlew check` + `rtk context-init update . --compact`.
```
