package dev.mimir.core

import kotlinx.coroutines.flow.StateFlow

enum class NetworkKind { NONE, WIFI, METERED }

interface NetworkState {
    val current: StateFlow<NetworkKind>
}
