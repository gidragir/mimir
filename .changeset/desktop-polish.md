---
"@mimir/core": minor
"@mimir/drive": minor
"@mimir/ui": minor
"@mimir/app-desktop": minor
"@mimir/app-android": minor
"@mimir/web-ui": minor
"@mimir/daemon": minor
"@mimir/obsidian-plugin": minor
"@mimir/browser-extension": minor
---

Доработка десктопа: вход в Google одной кнопкой через системный браузер (PKCE, loopback) с Device Code как запасным способом, отдельные OAuth-клиенты Desktop и Device со вшитыми значениями в `daemon`, подробный человеческий прогресс синхронизации и состояние «нет сети», ошибка одного файла не прерывает синхронизацию, исправление отрисовки окна под niri (`_JAVA_AWT_WM_NONREPARENTING`), защита от удаления локальных файлов при пустом или отстающем списке файлов в Google Drive, vault не ходит по символьным ссылкам и не синхронизирует `node_modules` и вложенные `.git`, а файлы за ссылками не считаются удалёнными; удалённые в Google Drive файлы попадают в корзину, а не стираются
