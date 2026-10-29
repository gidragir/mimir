package dev.mimir.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

interface VaultDiscovery {
    suspend fun findVaults(): List<String>
}

val LocalVaultDiscovery = staticCompositionLocalOf<VaultDiscovery?> { null }
