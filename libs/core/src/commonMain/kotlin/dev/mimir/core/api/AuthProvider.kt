package dev.mimir.core

data class Account(val email: String, val displayName: String?)

interface AuthProvider {
    suspend fun currentAccount(): Account?
    suspend fun signIn(): Account
    suspend fun signOut()
}
