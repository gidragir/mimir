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
import kotlin.time.Instant

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val mimirApp = app as? MimirApplication
    val runtime: MimirRuntime = mimirApp?.runtime ?: AndroidRuntimeFactory.create(app)
    private val secretStore = AndroidKeystoreSecretStore(app)
    val networkState = AndroidNetworkState(app)

    private val backgroundSyncFlow: StateFlow<UiBackgroundSync?> = combine(
        runtime.status,
        runtime.vaultPathFlow,
        networkState.current,
    ) { _, vaultPath, networkKind ->
        backgroundSyncFor(vaultPath, runtime.lastSyncTime, networkKind)
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

    companion object {
        internal fun backgroundSyncFor(
            vaultPath: String?,
            lastRun: Instant?,
            networkKind: NetworkKind,
        ): UiBackgroundSync? {
            return if (vaultPath != null) {
                UiBackgroundSync(
                    enabled = true,
                    lastRun = lastRun,
                    waitingForWifi = networkKind == NetworkKind.METERED,
                )
            } else {
                null
            }
        }
    }
}

