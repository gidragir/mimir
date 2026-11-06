package dev.mimir.android.auth

interface GisAuthorizer {
    /** Токен без UI; null — нужен интерактивный вход (hasResolution). */
    suspend fun authorizeSilently(): String?
    suspend fun clearToken(token: String)
    suspend fun revoke()
    fun isAvailable(): Boolean // GoogleApiAvailability == SUCCESS
    fun hasSavedAccount(): Boolean = false
}
