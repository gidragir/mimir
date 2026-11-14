package dev.mimir.core

import dev.mimir.core.testing.TestCiphers as CachedTestCiphers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcloneCipherKeyExportAndCanaryTest {
    @Test
    fun exportedKeyHexRestoresIdenticalCipherWithoutScrypt() = runTest {
        val original = CachedTestCiphers.forPassword("correct-horse-battery-staple")
        val hex = original.exportKeyHex()
        assertEquals(160, hex.length)

        val restored = RcloneCipher.fromKeyHex(hex)
        val encryptedName = original.encryptName("Проекты")
        assertEquals("Проекты", restored.decryptName(encryptedName))

        val plainBuffer = Buffer().apply { writeString("# Заметка\nТекст") }
        val encryptedStream = original.encrypt(plainBuffer)
        assertEquals("# Заметка\nТекст", restored.decrypt(encryptedStream).readString())
    }

    @Test
    fun fromKeyBytesRejectsInvalidLength() {
        assertFailsWith<IllegalArgumentException> {
            RcloneCipher.fromKeyBytes(ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            RcloneCipher.fromKeyHex("deadbeef")
        }
    }

    @Test
    fun canaryVerifierRejectsWrongPasswordLocallyWhenKnownHashProvided() = runTest {
        val rightCipher = CachedTestCiphers.forPassword("secret-1")
        val wrongCipher = CachedTestCiphers.forPassword("secret-2")
        val knownHash = CanaryVerifier.computeCanaryHash(rightCipher)
        val remote = CountingRemoteStorage(InMemoryRemoteStorage())

        val result = CanaryVerifier.verifyReadOnly(remote, wrongCipher, knownCanaryHash = knownHash)
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(result)
        assertEquals(0, remote.statCalls + remote.readCalls + remote.listCalls)
    }

    @Test
    fun canaryVerifierValidatesRemoteCanaryFileAndRejectsWrongCipher() = runTest {
        val rightCipher = CachedTestCiphers.forPassword("vault-pass")
        val wrongCipher = CachedTestCiphers.forPassword("wrong-pass")
        val remote = InMemoryRemoteStorage()
        val body = Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), rightCipher.encrypt(body))

        val valid = CanaryVerifier.verifyReadOnly(remote, rightCipher)
        assertIs<CanaryVerifier.CanaryVerifyResult.Valid>(valid)
        assertEquals(CanaryVerifier.computeCanaryHash(rightCipher), valid.canaryHash)

        val invalid = CanaryVerifier.verifyReadOnly(remote, wrongCipher)
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(invalid)
    }

    @Test
    fun canaryVerifierHandlesLegacyVaultWithoutCanaryFileByTestingEntryNames() = runTest {
        val rightCipher = CachedTestCiphers.forPassword("legacy-pass")
        val wrongCipher = CachedTestCiphers.forPassword("wrong-pass")
        val remote = InMemoryRemoteStorage()
        val encName = rightCipher.encryptName("Заметка.md")
        remote.write(RemotePath(encName), rightCipher.encrypt(Buffer().apply { writeString("hello") }))

        assertIs<CanaryVerifier.CanaryVerifyResult.Valid>(CanaryVerifier.verifyReadOnly(remote, rightCipher))
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(CanaryVerifier.verifyReadOnly(remote, wrongCipher))
    }

    @Test
    fun verifyReadOnlyRethrowsCancellationException() = runTest {
        val cipher = CachedTestCiphers.forPassword("cancel-pass")
        val raw = InMemoryRemoteStorage()
        raw.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(Buffer().apply { writeString(CanaryVerifier.CANARY_BODY) }))
        val cancellingRemote = object : RemoteStorage by raw {
            override suspend fun read(path: RemotePath): Source = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            CanaryVerifier.verifyReadOnly(cancellingRemote, cipher)
        }
    }

    @Test
    fun verifyOrInitWritesCanaryOnEmptyVaultAndRejectsWrongKnownHashWithoutNetwork() = runTest {
        val rightCipher = CachedTestCiphers.forPassword("init-pass")
        val wrongCipher = CachedTestCiphers.forPassword("wrong-init-pass")
        val raw = InMemoryRemoteStorage()
        val counting = CountingRemoteStorage(raw)

        val initResult = CanaryVerifier.verifyOrInit(counting, rightCipher)
        val valid = assertIs<CanaryVerifier.CanaryVerifyResult.Valid>(initResult)
        assertEquals(CanaryVerifier.computeCanaryHash(rightCipher), valid.canaryHash)
        assertEquals(1, counting.writeCalls)

        // Проверка с неверным knownCanaryHash без сетевых вызовов
        val beforeCalls = counting.statCalls + counting.readCalls + counting.listCalls + counting.writeCalls
        val rejected = CanaryVerifier.verifyOrInit(counting, wrongCipher, knownCanaryHash = valid.canaryHash)
        assertIs<CanaryVerifier.CanaryVerifyResult.Invalid>(rejected)
        assertEquals(beforeCalls, counting.statCalls + counting.readCalls + counting.listCalls + counting.writeCalls)
    }
}
