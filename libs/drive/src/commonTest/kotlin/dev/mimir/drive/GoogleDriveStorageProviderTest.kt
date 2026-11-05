package dev.mimir.drive

import dev.mimir.core.RemotePath
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleDriveStorageProviderTest {
    @Test
    fun `provider id is gdrive`() = assertEquals("gdrive", GoogleDriveStorageProvider.id)

    @Test
    fun `scopes are exactly drive file openid email profile`() = assertEquals(
        listOf("https://www.googleapis.com/auth/drive.file", "openid", "email", "profile"),
        GoogleAuthConfig.scopes,
    )

    @Test
    fun `create returns storage whose read throws NotImplementedError`() = runTest {
        val storage = GoogleDriveStorageProvider.create(emptyMap())
        assertFailsWith<NotImplementedError> { storage.read(RemotePath("a.md")) }
    }
}
