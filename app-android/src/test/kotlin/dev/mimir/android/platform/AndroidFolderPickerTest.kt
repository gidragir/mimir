package dev.mimir.android.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFolderPickerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `cancelled picker returns null`() = runBlocking {
        val picker = AndroidFolderPicker(context, launchPicker = { null })
        val result = picker.pickDirectory()
        assertNull(result)
    }

    @Test
    fun `untranslatable uri throws IllegalArgumentException with standard message`() = runBlocking {
        val picker = AndroidFolderPicker(
            context,
            launchPicker = { Uri.parse("content://com.google.android.apps.docs.storage/tree/cloud-folder") },
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            picker.pickDirectory()
        }
        assertEquals(TreeUriPaths.ERROR_UNTRANSLATABLE, ex.message)
    }

    @Test
    fun `local primary uri resolves to file path`() = runBlocking {
        val picker = AndroidFolderPicker(
            context,
            launchPicker = {
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FVault")
            },
        )
        val result = picker.pickDirectory()
        val expected = "${Environment.getExternalStorageDirectory().absolutePath}/Documents/Vault"
        assertEquals(expected, result)
    }

    @Test
    fun `picker via ActivityResultRegistry launches contract and resolves path`() = runBlocking {
        val testRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(
                    requestCode,
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FVault"),
                )
            }
        }
        val picker = AndroidFolderPicker(context, registry = testRegistry)
        val result = picker.pickDirectory()
        val expected = "${Environment.getExternalStorageDirectory().absolutePath}/Documents/Vault"
        assertEquals(expected, result)
    }
}
