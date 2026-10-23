# Mimir crypto (rclone crypt) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить `NotImplementedCipher` реализацией `RcloneCipher`, бит-в-бит совместимой с rclone crypt (имена по сегментам, содержимое блоками).

**Architecture:** Чистый Kotlin в `core/commonMain`: небольшие файлы-примитивы (SHA-256/HMAC/PBKDF2, Salsa20+scrypt, XSalsa20/Poly1305/secretbox, AES-256+EME, base32) и `RcloneCipher`, который собирает их. Корректность — стандартные векторы + golden-векторы от установленного rclone 1.75.2 (в репозитории лежат как Kotlin-файл с константами), плюс опциональный JVM-тест с живым rclone.

**Tech Stack:** Kotlin 2.4.21 multiplatform (jvm, js, wasmJs, android), kotlinx-io `Source`/`Buffer`, kotlin.test, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-crypto-design.md` (родительская: `2026-10-23-mimir-design.md`, раздел 6.3; при конфликте права она).

## Global Constraints

- Пакет всех файлов `dev.mimir.core` (как у существующего `Cipher`), файлы лежат в `core/src/commonMain/kotlin/dev/mimir/core/crypto/`; тесты в `core/src/commonTest/kotlin/dev/mimir/core/` (путь/пакет как у `EncryptedRemoteStorageTest`).
- `core` — чистый Kotlin без платформенных зависимостей; новых библиотек не добавлять. Единственный `expect/actual` — источник случайности.
- scrypt: N=16384, r=8, p=1, dkLen=80; соль — `password2` (UTF-8) или стандартная `A8 0D F4 3A 8F BD 03 08 A7 CA B8 3E 58 1F 86 B1`; ключи: `dataKey`=[0,32), `nameKey`=[32,64), `nameTweak`=[64,80).
- Файл: `"RCLONE\x00\x00"` + nonce 24 байта + блоки ≤ 65536 байт открытого текста → secretbox (тег 16 байт); nonce блока n = nonce + n (192-бит LE с переносом); пустой файл — 32 байта заголовка.
- Имя сегмента: UTF-8 → PKCS#7 до 16 → EME(AES-256, `nameKey`, tweak `nameTweak`) → base32 алфавит `0-9a-v`, нижний регистр, без `=`; пустой сегмент остаётся пустым.
- Пустой пароль → `IllegalArgumentException`; ошибки формата/подлинности → `CryptException` без секретов в сообщении; тег Poly1305 сравнивается за постоянное время; ключи не попадают в `toString()`/логи.
- Gradle только так: `mise x java@temurin-27 -- ./gradlew …`; js-тесты с `CHROME_BIN=/usr/bin/chromium`. На Windows ничего не запускать.
- TDD: сначала падающий тест, потом код. Коммиты conventional + trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Версию в `gradle.properties` руками не менять; изменение сопровождается `pnpm changeset`.
- Сборка и тесты проходят без секретов, без сети, без rclone, с Android SDK и без.

## Review Focus

1. Файл ровно 65536 (и 131072) байт — rclone не пишет пустой хвостовой блок; ждём расшифровку без ошибки и тот же шифртекст (Task 6).
2. `Source` читают по 1 байту / по произвольным кускам, а не одним `readByteArray()` — результат тот же (Task 6).
3. Неверный пароль или порча одного байта (заголовок, тег, тело) → `CryptException`, а не мусор и не другое исключение (Task 6).
4. Имена: кириллица, пробелы, эмодзи, сегмент > 143 байт шифртекста, пустой сегмент, мусорный/не-base32 шифртекст при расшифровке → корректный round-trip или `CryptException` (Task 5).
5. Первая загрузка scrypt на js не должна падать по таймауту mocha/karma (Task 5, шаг с `karma.config.d`).

---

### Task 1: SHA-256, HMAC-SHA256, PBKDF2

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/Sha256.kt`, `HmacSha256.kt`, `Pbkdf2.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/HashTest.kt`

**Interfaces:**
- Produces: `internal object Sha256 { fun digest(data: ByteArray): ByteArray }`; `internal object HmacSha256 { fun mac(key: ByteArray, data: ByteArray): ByteArray }`; `internal object Pbkdf2 { fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray }`. Общий хелпер `internal fun String.hexToBytes(): ByteArray` и `internal fun ByteArray.toHex(): String` — в `core/src/commonTest/.../Hex.kt` (используется всеми тестами; в main не нужен).

- [ ] **Step 1: Write failing tests** `HashTest`: `sha256 of abc` (`ba7816bf…15ad`), `sha256 of empty`, `sha256 across block boundary` (вход 56 и 64 байта «a»: векторы из FIPS 180-4/NIST), `hmac rfc4231 case 1`, `hmac with key longer than block` (RFC 4231 case 6), `pbkdf2 rfc7914 section 11` («passwd»/«salt», c=1, dkLen=64). Векторы брать из RFC/NIST дословно.
- [ ] **Step 2: Run** `mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*HashTest*'` → FAIL (unresolved reference).
- [ ] **Step 3: Implement** три объекта по сигнатурам выше (чистый Kotlin, `UInt`-арифметика); PBKDF2 собирает блоки `HMAC(pw, salt‖INT_32_BE(i))`.
- [ ] **Step 4: Run** тот же тест → PASS.
- [ ] **Step 5: Commit** `feat(core): sha256, hmac and pbkdf2 primitives`.

### Task 2: Salsa20 core и scrypt

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/Salsa20.kt`, `Scrypt.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/ScryptTest.kt`

**Interfaces:**
- Consumes: `Pbkdf2.hmacSha256` (Task 1).
- Produces: `internal object Salsa20 { fun core(input: IntArray /*16 слов*/, rounds: Int): IntArray; fun hsalsa20(key: ByteArray /*32*/, nonce16: ByteArray): ByteArray /*32*/ }`; `internal object Scrypt { fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray }`.

- [ ] **Step 1: Write failing tests** `ScryptTest`: четыре вектора RFC 7914 §12 по именам `rfc7914 vector 1..4` (последний — N=16384,r=8,p=1 «pleaseletmein»/«SodiumChloride», 64 байта; вектор 5 с N=1048576 НЕ брать), `hsalsa20 nacl vector` (из NaCl/libsodium `tests/core*`), `rejects n not power of two` (`IllegalArgumentException`). Векторы копировать из RFC/NaCl дословно.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*ScryptTest*'` → FAIL.
- [ ] **Step 3: Implement** `Salsa20.core` (двойной раунд по спецификации, результат = слова + вход), `Scrypt.derive` (PBKDF2 → ROMix с BlockMix на `Salsa20.core(…, 8)` → PBKDF2). Память `128*r*n` байт одним массивом.
- [ ] **Step 4: Run** → PASS на jvm; затем `… :core:jsTest :core:wasmJsTest` с `CHROME_BIN` — те же тесты PASS (если js падает по таймауту — это решается в Task 5, пока допустимо пропустить N=16384 вектор на js через `expect`-флаг не нужно: просто повторить после Task 5).
- [ ] **Step 5: Commit** `feat(core): salsa20 core and scrypt`.

### Task 3: XSalsa20, Poly1305, secretbox

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/XSalsa20.kt`, `Poly1305.kt`, `SecretBox.kt`, `CryptException.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SecretBoxTest.kt`

**Interfaces:**
- Consumes: `Salsa20.core`, `Salsa20.hsalsa20` (Task 2).
- Produces: `class CryptException(message: String) : Exception(message)` (public, в `dev.mimir.core`); `internal object Poly1305 { fun mac(key32: ByteArray, data: ByteArray): ByteArray /*16*/ }`; `internal object SecretBox { fun seal(key: ByteArray, nonce24: ByteArray, plain: ByteArray): ByteArray /*tag16 ‖ cipher*/; fun open(key: ByteArray, nonce24: ByteArray, box: ByteArray): ByteArray /*бросает CryptException при неверном теге или len<16*/ }`.

- [ ] **Step 1: Write failing tests**: `poly1305 rfc8439 vector`, `secretbox nacl vector` (тест-вектор NaCl «firstkey/nonce/…» из `tests/secretbox.c`), `open rejects flipped tag byte`, `open rejects flipped body byte`, `open rejects short input`, `seal then open roundtrip for lengths 0,1,63,64,65,65536`.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*SecretBoxTest*'` → FAIL.
- [ ] **Step 3: Implement.** XSalsa20: subkey = `hsalsa20(key, nonce[0..16])`, затем Salsa20-поток с nonce[16..24]; первые 32 байта потока — ключ Poly1305, данные шифруются со смещения 32 (как в NaCl `crypto_secretbox`). Poly1305 на 26-битных лимбах или `Long` по RFC 8439; сравнение тега — XOR-накопление без раннего выхода.
- [ ] **Step 4: Run** → PASS (jvm; js/wasm — в Task 8).
- [ ] **Step 5: Commit** `feat(core): xsalsa20-poly1305 secretbox`.

### Task 4: AES-256, EME, base32

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/Aes256.kt`, `Eme.kt`, `Base32Hex.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/NameCryptoTest.kt`

**Interfaces:**
- Produces: `internal class Aes256(key: ByteArray) { fun encryptBlock(b: ByteArray): ByteArray; fun decryptBlock(b: ByteArray): ByteArray }`; `internal class Eme(key: ByteArray) { fun encrypt(tweak: ByteArray /*16*/, data: ByteArray /*16*k, k 1..128*/): ByteArray; fun decrypt(tweak: ByteArray, data: ByteArray): ByteArray }`; `internal object Base32Hex { fun encode(data: ByteArray): String; fun decode(text: String): ByteArray /*CryptException при неверном символе/длине*/ }`.

- [ ] **Step 1: Write failing tests**: `aes256 fips197 vector` (C.3), `base32hex rfc4648 vectors` (RFC 4648 §10, в нижнем регистре без `=`: "", "f", "fo", … "foobar"), `base32hex decode rejects invalid char`, `eme roundtrip for 1..8 blocks`, `eme rejects non multiple of 16` (`IllegalArgumentException`). Совпадение EME с rclone проверяется golden-именами в Task 5 — здесь только свойства.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*NameCryptoTest*'` → FAIL.
- [ ] **Step 3: Implement.** AES — табличная реализация или S-box с вычисляемыми таблицами (допустим любой корректный). EME-32 по Halevi–Rogaway как в `github.com/rfjakob/eme` (умножение на 2 в GF(2^128), little-endian, как в той реализации; порядок: ECB-слой, MP, mix, ECB-слой); ключевая деталь — `multByTwo` для rfjakob/eme работает над байтами в порядке little-endian (сверить с golden в Task 5, при расхождении править здесь).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(core): aes-256, eme and base32hex`.

### Task 5: RcloneCipher — ключи и имена, golden-векторы имён

**Files:**
- Create: `scripts/gen-rclone-vectors.sh`, `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`, `core/karma.config.d/mocha-timeout.js`
- Create (генерируется скриптом, коммитится): `core/src/commonTest/kotlin/dev/mimir/core/RcloneVectors.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherNamesTest.kt`

**Interfaces:**
- Consumes: `Scrypt.derive`, `Eme`, `Base32Hex`, `CryptException`.
- Produces: `class RcloneCipher(password: String, password2: String? = null, nonceSource: () -> ByteArray = ::randomNonce) : Cipher` — в этой задаче реализованы только `encryptName`/`decryptName`, `encrypt`/`decrypt` бросают `NotImplementedError("content crypto pending")` до Task 6; `init` бросает `IllegalArgumentException` при пустом `password`; `toString()` = `"RcloneCipher"`. `internal expect fun randomNonce(): ByteArray` объявляется в Task 6 — здесь временно параметр `nonceSource` без значения по умолчанию.
- Produces (данные): в `RcloneVectors.kt` — `object RcloneVectors { class Config(val password: String, val password2: String?); val configs: List<Config>; val names: List<NameVector> (config index, plain, encrypted); val files: List<FileVector> (config index, size, encryptedBase64) }`; открытый текст файла детерминирован: байт i = `(i * 7 + 3) and 0xFF`.

- [ ] **Step 1: Write `scripts/gen-rclone-vectors.sh`** (bash, `rclone` ≥ 1.60, `set -euo pipefail`, временный каталог в `mktemp -d`): для двух конфигураций (`password` = «mimir-test-pass» без password2; та же + `password2` = «mimir-test-salt») создаёт крипт-ремоут через connection string (`:crypt,remote=<tmp>,password=$(rclone obscure …),password2=…:`), копирует `rclone copyto` файлы размеров 0, 1, 65535, 65536, 65537, 131073 (содержимое по формуле выше), получает пары имя↔шифр для набора: `a.md`, `Здоровье`, `Анализы мамы.md`, `заметка 😀.md`, имя из 200 символов `x`, `.obsidian` — через `rclone cryptdecode --reverse` и пишет `RcloneVectors.kt` (base64 файлов режется на константы ≤ 8000 символов и склеивается `joinToString("")` — JVM-ограничение на длину строковой константы). Скрипт идемпотентен, выводит путь результата.
- [ ] **Step 2: Run** `bash scripts/gen-rclone-vectors.sh` → создан `RcloneVectors.kt`; просмотреть: имена в нижнем регистре base32, размеры файлов ожидаемые (`32 + size + 16 * ceil(size/65536)`).
- [ ] **Step 3: Write failing tests** `RcloneCipherNamesTest` (cipher на каждую конфигурацию создаётся один раз — scrypt дорог): `names match rclone golden` (для каждого `NameVector`: `encryptName(plain) == encrypted` и `decryptName(encrypted) == plain`), `empty segment stays empty`, `long name roundtrips` (200 символов, 300 байт UTF-8), `emoji and cyrillic roundtrip`, `decrypt rejects non base32` / `decrypt rejects length not multiple of 16 bytes` / `decrypt rejects bad padding` (все `CryptException`), `empty password rejected` (`IllegalArgumentException`), `password2 changes names`, `toString hides keys`.
- [ ] **Step 4: Run** `… :core:jvmTest --tests '*RcloneCipherNamesTest*'` → FAIL.
- [ ] **Step 5: Implement `RcloneCipher`** по сигнатуре: ключи через `Scrypt.derive` в `init`, разделение на `dataKey`/`nameKey`/`nameTweak`; PKCS#7 с проверкой при расшифровке. Если golden-имена не совпадают — разбирать в `Eme` (Task 4), а не подгонять тест.
- [ ] **Step 6: Karma timeout.** Создать `core/karma.config.d/mocha-timeout.js`: `config.client = config.client || {}; config.client.mocha = { timeout: 120000 };` Run `CHROME_BIN=/usr/bin/chromium mise x java@temurin-27 -- ./gradlew :core:jsTest :core:wasmJsTest` → PASS, включая scrypt-векторы Task 2 и golden-имена; записать время scrypt на js в ledger; при > 60 с — Ruling.
- [ ] **Step 7: Run** `… :core:jvmTest` целиком → PASS.
- [ ] **Step 8: Commit** `feat(core): RcloneCipher key derivation and name encryption with rclone golden vectors`.

### Task 6: RcloneCipher — потоковое содержимое

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/crypto/RandomNonce.kt` (+ `jvmMain`/`jsMain`/`wasmJsMain`/`androidMain` actual — в `core/src/<target>Main/kotlin/dev/mimir/core/crypto/RandomNonce.<target>.kt`)
- Test: `core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherContentTest.kt`

**Interfaces:**
- Consumes: `SecretBox`, `RcloneVectors.files` (Task 5).
- Produces: `internal expect fun randomNonce(): ByteArray` (24 байта CSPRNG: jvm/android `SecureRandom`, js `crypto.getRandomValues`, wasmJs — через `js("crypto.getRandomValues")`-интероп); `encrypt(plain: Source): Source`/`decrypt(encrypted: Source): Source` в `RcloneCipher` — ленивые `RawSource`-обёртки, читают вход блоками по 65536 (шифртекст — 65552), `nonceSource` вызывается один раз на `encrypt`; `decrypt` при плохой магии/коротком заголовке/неверном теге/обрезанном внутри блока теле бросает `CryptException` (при чтении, не при создании).

- [ ] **Step 1: Write failing tests** `RcloneCipherContentTest` (общие cipher-ы, как в Task 5): `decrypts rclone golden files` (для всех `FileVector`: результат == детерминированный открытый текст), `encrypts byte for byte like rclone` (nonce = байты 8..32 эталонного файла, `nonceSource = { nonce }`; результат == эталон — для всех размеров), `empty file is 32 byte header`, `exact multiple of block size has no empty trailing block` (65536 и 131072 → длины 65536+16+32 и 131072+32+32; roundtrip), `reading in 1 byte and odd chunks gives same result` (читать `Source.readByteArray(1)` до конца / по 7777 байт — Review Focus 2), `wrong password fails` (`CryptException` на первом чтении файла ≥ 1 байта), `flipped byte in header magic / nonce / tag / body fails`, `truncated inside block fails`, `truncated header fails`, `nonce increments with carry` (nonce с хвостом `FF FF` для 3 блоков: roundtrip и блоки отличаются), `nonceSource of wrong length is rejected` (`IllegalArgumentException`), `random nonce differs between encryptions`.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*RcloneCipherContentTest*'` → FAIL (NotImplementedError).
- [ ] **Step 3: Implement** `encrypt`/`decrypt` и `randomNonce` (expect + 4 actual) по сигнатурам; инкремент nonce — отдельная `private fun nextNonce(base: ByteArray, n: Long)` (LE, перенос по всем 24 байтам).
- [ ] **Step 4: Run** `CHROME_BIN=/usr/bin/chromium mise x java@temurin-27 -- ./gradlew :core:allTests` → PASS на jvm, js, wasmJs.
- [ ] **Step 5: Commit** `feat(core): streaming rclone content encryption`.

### Task 7: Опциональный интеграционный тест с живым rclone (jvm)

**Files:**
- Test: `core/src/jvmTest/kotlin/dev/mimir/core/RcloneInteropTest.kt`

**Interfaces:**
- Consumes: `RcloneCipher`; запускает `rclone` через `ProcessBuilder`.

- [ ] **Step 1: Write the test** `rclone decrypts what mimir encrypted`: если `rclone` нет в PATH (проверка `rclone version` → IOException/ненулевой код) — тест печатает `SKIPPED: rclone not found` и возвращается; иначе: во временном каталоге раскладывает файл `Здоровье/Анализы мамы.md` (имя по сегментам и содержимое — нашим `RcloneCipher`), вызывает `rclone copy` через `:crypt,remote=<dir>,password=<obscure>:` в другой каталог и сравнивает содержимое и имя. Обратный тест `mimir decrypts what rclone encrypted` — симметрично.
- [ ] **Step 2: Run** `mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*RcloneInteropTest*'` → PASS (rclone на машине есть); временно `PATH` без rclone → тест SKIPPED-сообщение, зелёный.
- [ ] **Step 3: Commit** `test(core): optional rclone interop test`.

### Task 8: Интеграция, удаление заглушки, документация

**Files:**
- Delete: `core/src/commonMain/kotlin/dev/mimir/core/crypto/NotImplementedCipher.kt`
- Modify: `browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt`, `README.md`, `AGENTS.md`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/EncryptedRemoteStorageRcloneTest.kt`
- Create: `.changeset/<имя>.md` через `pnpm changeset`

**Interfaces:**
- Consumes: `RcloneCipher`, `EncryptedRemoteStorage`, `InMemoryRemoteStorage`.

- [ ] **Step 1: Write failing test** `EncryptedRemoteStorageRcloneTest.stored data is encrypted per segment and reads back`: путь `Здоровье/Анализы мамы.md`; в `inner` лежат ровно два зашифрованных сегмента (`inner.list` корня → один элемент, имя совпадает с `cipher.encryptName("Здоровье")`, не содержит кириллицы), содержимое в `inner` начинается с `RCLONE\0\0`, через `EncryptedRemoteStorage` читается исходное; `list` расшифровывает имена.
- [ ] **Step 2: Run** `… :core:jvmTest --tests '*EncryptedRemoteStorageRcloneTest*'` → PASS либо FAIL; если сразу PASS — тест всё равно оставить (он фиксирует спецификацию 8.4).
- [ ] **Step 3: Remove `NotImplementedCipher`** и заменить использование в `Popup.kt`: `decryptionMessage()` возвращает константную строку «Расшифровка недоступна: нет пароля» без обращения к шифру; убрать импорт.
- [ ] **Step 4: Docs.** README: раздел «Совместимость с rclone» (параметры шифра из Global Constraints, пример `rclone` connection string для ручной расшифровки vault: `:crypt,remote=<путь к копии из Drive>,password=$(rclone obscure …)[,password2=…]:`, режимы `filename_encryption=standard`, `directory_name_encryption=true`, `filename_encoding=base32`; оговорка о размерах/времени и о том, что усечение файла по границе блока не обнаруживается — как у rclone). AGENTS.md: строка «Реальной синхронизации, шифрования и OAuth в скелете нет…» → шифрование реализовано (`RcloneCipher`), заглушки остаются для синхронизации и OAuth.
- [ ] **Step 5: Changeset.** `pnpm changeset` (minor, `@mimir/core`) с описанием «rclone-compatible crypto».
- [ ] **Step 6: Full verification:** `CHROME_BIN=/usr/bin/chromium mise x java@temurin-27 -- ./gradlew build allTests` без `ANDROID_HOME` (должно быть предупреждение `Android SDK not found: skipping Android targets`), затем с `ANDROID_HOME=~/Android/Sdk` → всё зелёное; `mise x java@temurin-27 -- ./gradlew :browser-extension:checkExtension` → OK.
- [ ] **Step 7: Commit** `feat(core): wire rclone crypto, drop NotImplementedCipher` (+ отдельный `docs:`-коммит для README/AGENTS допустим).
