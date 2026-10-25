---
name: mimir-sync-io
description: Use when Mimir code reads or writes vault files, calls Google Drive or any RemoteStorage, changes the sync engine, plan, scheduler, watcher, uploader or DriveHttpClient, or adds a loop over files — slow sync, quota or rate limit (429, 403 rateLimitExceeded), large vaults, big attachments, high memory, UI freezes, battery or mobile data.
---

# Асинхронность и стоимость IO в Mimir

## Суть

Drive — медленное квотируемое сетевое хранилище; vault — десятки тысяч файлов и вложения в сотни МБ; на телефоне батарея и мобильный трафик. Стоимость решения измеряется в **запросах к Drive и прочитанных байтах на N файлов** и должна расти от числа изменений, а не от размера vault.

## До кода: бюджет

В спеке/плане пункт **«IO»**: сколько запросов к Drive и полных чтений файлов на прогон для vault из N файлов при K изменениях; что идёт параллельно и с каким пределом; на каком диспетчере блокирующая работа.

## Правила

| Тема | Как делать | Эталон / заметка |
|---|---|---|
| Листинг | один постраничный `listAll` (`pageSize=1000`, узкий `fields=`), не рекурсивный `list` по папкам | `GoogleDriveStorage.listAll` |
| Изменения | при `capabilities.changeFeed` — `getChanges(token)`, не полный листинг | сейчас лента только будит `SyncScheduler` |
| Сравнение | по индексу: `SyncIndex` (hash, revision), размер, `modifiedTime`, `md5Checksum` шифротекста (nonce случайный: сравнивается только с `revision` в индексе, не с локальным файлом); содержимое — крайний случай | дешёвая проверка размер+mtime до хэширования |
| Данные | потоки `kotlinx.io.Source`/`Sink`; `readByteArray()` — только для заведомо малых данных | большие файлы — resumable (`DriveUploader`, порог 5 МБ) |
| Параллелизм | независимые действия — с ограничением (`Semaphore`, `flatMapMerge(concurrency)`, 4–8, настраиваемо); порядок «папка до содержимого» и проверка перед удалением сохраняются; индекс обновляется под `Mutex` | без предела — нельзя: квоты Drive |
| Диспетчеры | блокирующая ФС и CPU (scrypt, sha, шифр) — не в UI-потоке: `withContext(Dispatchers.IO/Default)` на границе адаптера/демона; в `core/commonMain` диспетчер внедряется параметром (в JS нет IO) | `DaemonRuntime` оборачивает `list()` в `Dispatchers.IO` |
| Отмена | кооперативная: `ensureActive()` в длинных циклах, `CancellationException` пробрасывается | `DefaultSyncEngine` |
| Ошибки и квоты | 429, 403 `rateLimitExceeded`/`userRateLimitExceeded`, 5xx → экспоненциальная пауза с джиттером, учитывать `Retry-After`; нет сети → `RemoteUnavailableException`, без циклов ожидания | `DriveHttpClient.executeWithRetry` |
| События ФС | слияние и задержка (debounce) событий; запись Mimir не будит синхронизацию повторно | `EchoFilter`, `SyncScheduler` |
| Кэши и клиенты | один `HttpClient`, переиспользование токена, кэш путь→id с явной инвалидацией | `DrivePathResolver`; `listAll` сбрасывает кэш |
| Прогресс | обновлять агрегатами, не копировать списки на каждый файл из 10 000 | `SyncProgress.UPCOMING_LIMIT` |

## Тесты стоимости

- Бюджет фиксируется тестом: считать HTTP-запросы через Ktor `MockEngine` (как `GoogleDriveStorageListAllTest`) или вызовы обёртки-счётчика над `InMemoryRemoteStorage`, для N файлов и K изменений.
- Прогон без изменений не скачивает ни одного файла.
- Параллелизм и паузы — на виртуальном времени (`runTest`), без реальных `delay`.

## Известный долг (не копировать как образец)

- Оставшиеся пункты §10 спеки и `changeFeed` (инкрементальный список изменений Drive вместо полного `listAll`).
- Эталоны правильного: `IndexUpdater` (пакетное сохранение индекса поверх `SyncStateStore`), `RetryPolicy` (джиттер, `Retry-After`, 403 rate limit), `CountingRemoteStorage` (бюджет вызовов в тестах).

## Красные флаги

- `for (f in files) remote.stat/read(...)`; `remoteStorage.read` на этапе сравнения.
- `readByteArray()` на файле vault; `runBlocking`, `Thread.sleep`, `GlobalScope`.
- `launch` на каждый файл без предела; запрос без `fields=`.
- «Работает на моём vault из 50 заметок».
