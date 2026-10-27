package dev.mimir.daemon

import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
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
        verifyOrInitCanary(remote, RcloneCipher.create("correct-pass"), dir)
        assertNotNull(remote.stat(RemotePath(CANARY_FILENAME)))
    }

    @Test
    fun `accepts the same password on an existing canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, RcloneCipher.create("password-A"), dir)
        verifyOrInitCanary(remote, RcloneCipher.create("password-A"), dir)
    }

    @Test
    fun `fails unlock when wrong password is used on existing canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, RcloneCipher.create("password-A"), dir)
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, RcloneCipher.create("password-B-wrong"), dir)
        }
    }

    @Test
    fun `fails unlock when remote has files but no canary`() = runTest {
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath("some_existing_file"), Buffer().also { it.write(byteArrayOf(1, 2, 3)) })
        assertFailsWith<InvalidPasswordException> {
            verifyOrInitCanary(remote, RcloneCipher.create("any-pass"), dir)
        }
    }

    @Test
    fun `offline unlock is accepted only with the previously verified password`() = runTest {
        val remote = InMemoryRemoteStorage()
        verifyOrInitCanary(remote, RcloneCipher.create("password-A"), dir)
        val offline = object : dev.mimir.core.RemoteStorage by remote {
            override suspend fun stat(path: RemotePath) = throw IOException("offline")
        }
        verifyOrInitCanary(offline, RcloneCipher.create("password-A"), dir)
        assertFailsWith<InvalidPasswordException> { verifyOrInitCanary(offline, RcloneCipher.create("password-B"), dir) }
    }

    @Test
    fun `offline first unlock without verified hash is rejected`() = runTest {
        val offline = object : dev.mimir.core.RemoteStorage by InMemoryRemoteStorage() {
            override suspend fun stat(path: RemotePath) = throw IOException("offline")
        }
        assertFailsWith<SyncNotConfiguredException> { verifyOrInitCanary(offline, RcloneCipher.create("p"), dir) }
        assertTrue(Files.notExists(dir.resolve(".canary_hash")))
    }

    @Test
    fun `password file readable by group or others is reported as insecure`() {
        val file = Files.createTempFile("mimir-pass", ".txt")
        Files.writeString(file, "secret")
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        kotlin.test.assertTrue(DaemonConfig(passwordFile = file.toString()).isPasswordFileInsecure())
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        kotlin.test.assertFalse(DaemonConfig(passwordFile = file.toString()).isPasswordFileInsecure())
    }
}
