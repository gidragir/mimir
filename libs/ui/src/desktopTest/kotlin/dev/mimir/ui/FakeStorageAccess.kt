package dev.mimir.ui

import dev.mimir.ui.platform.StorageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FakeStorageAccess(
    initialGranted: Boolean = false,
    private val refuseNotifications: Boolean = false,
) : StorageAccess {
    val grantedFlow = MutableStateFlow(initialGranted)
    override val granted: StateFlow<Boolean> = grantedFlow
    var requestAllFilesCalled = false
    var requestNotificationsCalled = false

    override fun requestAllFiles() {
        requestAllFilesCalled = true
    }

    override fun requestNotifications() {
        requestNotificationsCalled = true
        if (refuseNotifications) {
            // Имитация отказа в разрешении на уведомления.
        }
    }
}
