---
name: mimir-storage-adapter
description: Use when adding or changing a cloud storage adapter in Mimir (Google Drive now, others later) — port contracts, registry, encryption decorator and the test set an adapter must have.
---

# Адаптер облачного хранилища

## Контракт (`core`, пакет `dev.mimir.core`)

- `RemoteStorage`: `list`, `stat`, `read`, `write`, `delete` над `RemotePath` ("/"-разделённый, без ведущего "/", пустой — корень) и `kotlinx.io.Source`.
- `RemoteStorageProvider`: `id`, `displayName`, `capabilities: StorageCapabilities(changeFeed)`, `create(config)`.
- Регистрация: `RemoteStorageRegistry.register(provider)`; дубликат `id` → `IllegalArgumentException`.
- Эталон адаптера и фейк для тестов — `InMemoryRemoteStorage` (`core/testing`).

## Правила

- Адаптер живёт в своём модуле (как `drive`), зависит только от `:core`.
- Шифрование адаптер **не делает**: его добавляет `EncryptedRemoteStorage(inner, cipher)` — по сегментам пути и по содержимому. Адаптер видит уже зашифрованные пути.
- Область доступа минимальна: для Google — ровно `drive.file`, `openid`, `email`, `profile` (`GoogleAuthConfig.scopes`, спека 6.4); расширять только по спеке.
- Нереализованное бросает `NotImplementedError("<Adapter> adapter is not implemented yet")`.
- Секреты (OAuth client id/secret, токены) — через `SecretStore` и `mise.local.toml`/GitHub Secrets, не в коде.

## Тесты адаптера (минимум)

- `id`, `displayName`, `capabilities` провайдера.
- Константы доступа/скоупов сравниваются со спекой.
- `create(...)` возвращает хранилище с ожидаемым поведением (для заглушки — `NotImplementedError`).
- Для реального адаптера: поведение `list` (только прямые потомки), `stat` для отсутствующего пути (`null`), `write`→`read` round-trip — на общем наборе, который прогоняется и для `InMemoryRemoteStorage`.

Запуск: `./gradlew :<module>:allTests` (см. `mimir-build-verify`).
