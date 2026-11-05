package dev.mimir.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

sealed interface SystemLoginResult {
    data object Granted : SystemLoginResult
    data object Cancelled : SystemLoginResult
    data class Unavailable(val reason: String) : SystemLoginResult
}

interface SystemAccountLogin {
    suspend fun authorize(): SystemLoginResult
}

val LocalSystemAccountLogin = staticCompositionLocalOf<SystemAccountLogin?> { null }
