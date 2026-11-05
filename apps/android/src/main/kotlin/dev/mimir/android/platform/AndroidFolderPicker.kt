package dev.mimir.android.platform

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.mimir.ui.picker.PlatformFolderPicker
import kotlinx.coroutines.CompletableDeferred

class AndroidFolderPicker(
    private val context: Context,
    private val launchPicker: (suspend (initialUri: Uri?) -> Uri?)? = null,
    registry: ActivityResultRegistry? = null,
) : PlatformFolderPicker {

    private var pendingDeferred: CompletableDeferred<Uri?>? = null

    private val launcher: ActivityResultLauncher<Uri?>? = registry?.register(
        "mimir_folder_picker",
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        pendingDeferred?.complete(uri)
    }

    override suspend fun pickDirectory(initialPath: String?): String? {
        val uri: Uri? = if (launchPicker != null) {
            launchPicker.invoke(null)
        } else if (launcher != null) {
            val deferred = CompletableDeferred<Uri?>()
            pendingDeferred = deferred
            launcher.launch(null)
            deferred.await()
        } else {
            null
        }

        if (uri == null) return null

        val path = TreeUriPaths.toPath(uri, context)
            ?: throw IllegalArgumentException(TreeUriPaths.ERROR_UNTRANSLATABLE)

        return path
    }
}

@Composable
fun rememberAndroidFolderPicker(activity: ComponentActivity): AndroidFolderPicker {
    val pendingRef = remember { arrayOf<CompletableDeferred<Uri?>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        pendingRef[0]?.complete(uri)
    }

    return remember(activity) {
        AndroidFolderPicker(
            context = activity,
            launchPicker = { initialUri ->
                val deferred = CompletableDeferred<Uri?>()
                pendingRef[0] = deferred
                launcher.launch(initialUri)
                deferred.await()
            },
        )
    }
}

