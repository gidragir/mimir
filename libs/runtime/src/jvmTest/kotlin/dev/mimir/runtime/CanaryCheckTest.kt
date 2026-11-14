package dev.mimir.runtime


import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.TestCiphers
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanaryCheckTest {
    private val dir = Files.createTempDirectory("mimir-test-canary")

    @Test
    fun `initializes canary when remote is empty`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, TestCiphers.forPassword("correct-pass"), dir)
        assertNotNull(remote.stat(RemotePath(CANARY_FILENAME)))
    }

    @Test
    fun `accepts the same password on an existing canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, TestCiphers.forPassword("password-A"), dir)
        verifyOrInitCanary(remote, TestCiphers.forPassword("password-A"), dir)
    }

    @Test
    fun `fails unlock when wrong password is used on existing canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, TestCiphers.forPassword("password-A"), dir)
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, TestCiphers.forPassword("password-B-wrong"), dir)
        }
    }

    @Test
    fun `fails unlock when remote has files but no canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath("some_existing_file"), Buffer().also { it.write(byteArrayOf(1, 2, 3)) })
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, TestCiphers.forPassword("any-pass"), dir)
        }
    }

    @Test
    fun `offline unlock is accepted only with the previously verified password`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, TestCiphers.forPassword("password-A"), dir)
        val offline = object : dev.mimir.core.RemoteStorage by remote {
            override suspend fun stat(path: RemotePath) = throw IOException("offline")
        }
        verifyOrInitCanary(offline, TestCiphers.forPassword("password-A"), dir)
        assertFailsWith<InvalidPasswordException> { verifyOrInitCanary(offline, TestCiphers.forPassword("password-B"), dir) }
    }

    @Test
    fun `offline first unlock without verified hash is rejected`() = runTest {
        val offline = object : dev.mimir.core.RemoteStorage by InMemoryRemoteStorage() {
            override suspend fun stat(path: RemotePath) = throw IOException("offline")
        }
        assertFailsWith<SyncNotConfiguredException> { verifyOrInitCanary(offline, TestCiphers.forPassword("p"), dir) }
        assertTrue(Files.notExists(dir.resolve(".canary_hash")))
    }

    @Test
    fun `offline unlock is accepted when remote throws RemoteUnavailableException`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, TestCiphers.forPassword("password-A"), dir)
        val offline = object : dev.mimir.core.RemoteStorage by remote {
            override suspend fun stat(path: RemotePath) =
                throw dev.mimir.core.RemoteUnavailableException("Google Drive is unreachable")
        }
        verifyOrInitCanary(offline, TestCiphers.forPassword("password-A"), dir)
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(offline, TestCiphers.forPassword("password-B"), dir)
        }
    }
}
