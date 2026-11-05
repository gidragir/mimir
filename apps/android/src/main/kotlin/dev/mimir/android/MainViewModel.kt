package dev.mimir.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mimir.android.platform.AndroidNetworkState
import dev.mimir.core.AndroidKeystoreSecretStore
import dev.mimir.core.NetworkKind
import dev.mimir.runtime.MimirRuntime
import dev.mimir.ui.model.DaemonServiceUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiBackgroundSync
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val mimirApp = app as? MimirApplication
    val runtime: MimirRuntime = mimirApp?.runtime ?: AndroidRuntimeFactory.create(app)
    private val secretStore = AndroidKeystoreSecretStore(app)
    val networkState = AndroidNetworkState(app)

    private val backgroundSyncFlow: StateFlow<UiBackgroundSync?> = combine(
        runtime.status,
        networkState.current,
    ) { _, networkKind ->
        if (runtime.vaultPath != null) {
            val waitingForWifi = networkKind == NetworkKind.METERED && false
            UiBackgroundSync(
                enabled = true,
                lastRun = runtime.lastSyncTime,
                waitingForWifi = waitingForWifi,
            )
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val model: MimirUiModel = DaemonServiceUiModel(
        service = runtime,
        scope = viewModelScope,
        rememberPassword = { password ->
            viewModelScope.launch {
                secretStore.put("vault_password", password)
            }
        },
        backgroundSync = backgroundSyncFlow,
    )

    override fun onCleared() {
        super.onCleared()
        networkState.close()
    }
}

