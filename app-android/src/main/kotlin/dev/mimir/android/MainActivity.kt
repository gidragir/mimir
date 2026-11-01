package dev.mimir.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.ViewModelProvider
import dev.mimir.android.platform.AndroidFolderPicker
import dev.mimir.android.platform.rememberAndroidFolderPicker
import dev.mimir.android.platform.AndroidStorageAccess
import dev.mimir.android.platform.FileSystemVaultDiscovery
import dev.mimir.ui.Destination
import dev.mimir.ui.MimirApp
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.platform.LocalBackHandler
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.platform.LocalSystemAccountLogin
import dev.mimir.ui.platform.LocalVaultDiscovery

class MainActivity : ComponentActivity() {
    private val destinationState = mutableStateOf(Destination.Status)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateFromIntent(intent)

        setContent {
            val viewModel: MainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
            val accountPrefs = remember { getSharedPreferences("mimir_gis", Context.MODE_PRIVATE) }
            val systemAccountLogin = rememberActivitySystemAccountLogin(this, accountPrefs)
            val storageAccess = remember { AndroidStorageAccess(this) }
            val folderPicker = rememberAndroidFolderPicker(this)
            val vaultDiscovery = remember {
                val roots = listOfNotNull(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    Environment.getExternalStorageDirectory(),
                ).distinct()
                FileSystemVaultDiscovery(roots)
            }
            val uriHandler = remember { CustomTabsUriHandler(this) }

            CompositionLocalProvider(
                LocalSystemAccountLogin provides systemAccountLogin,
                LocalStorageAccess provides storageAccess,
                LocalFolderPicker provides folderPicker,
                LocalVaultDiscovery provides vaultDiscovery,
                LocalUriHandler provides uriHandler,
                LocalBackHandler provides { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                },
            ) {
                val destination by destinationState
                MimirApp(
                    model = viewModel.model,
                    touchInput = true,
                    initialDestination = destination,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFromIntent(intent)
    }

    private fun updateFromIntent(intent: Intent?) {
        if (intent == null) return
        val extra = intent.getStringExtra("destination")
        if (extra != null) {
            destinationState.value = Destination.fromString(extra)
        }
    }
}
