package dev.mimir.android.platform

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TreeUriPathsTest {

    private val volumes = mapOf(
        "primary" to "/storage/emulated/0",
        "1234-5678" to "/storage/1234-5678",
    )

    @Test
    fun `primary tree uri resolves to primary path`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FVault")
        val path = TreeUriPaths.toPath(uri, volumes)
        assertEquals("/storage/emulated/0/Documents/Vault", path)
    }

    @Test
    fun `tree uri on removable volume resolves to volume path`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3ANotes")
        val path = TreeUriPaths.toPath(uri, volumes)
        assertEquals("/storage/1234-5678/Notes", path)
    }

    @Test
    fun `non-local provider uri yields null`() {
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/tree/some-doc-id")
        val path = TreeUriPaths.toPath(uri, volumes)
        assertNull(path)
    }
}
