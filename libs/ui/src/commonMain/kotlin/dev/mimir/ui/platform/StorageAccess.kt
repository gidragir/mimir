package dev.mimir.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

interface StorageAccess {
    val granted: StateFlow<Boolean>
    fun requestAllFiles()
    fun requestNotifications()
}

val LocalStorageAccess = staticCompositionLocalOf<StorageAccess?> { null }
