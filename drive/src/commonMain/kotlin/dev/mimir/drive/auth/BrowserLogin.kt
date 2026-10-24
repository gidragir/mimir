package dev.mimir.drive.auth

import dev.mimir.core.Sha256
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

object Pkce {
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun newVerifier(): String {
        val bytes = Uuid.random().toByteArray() + Uuid.random().toByteArray()
        return base64Url.encode(bytes)
    }

    fun challenge(verifier: String): String =
        base64Url.encode(Sha256.digest(verifier.encodeToByteArray()))
}

data class BrowserLoginRequest(
    val authUrl: String,
    val redirectUri: String,
    val state: String,
    val verifier: String,
)

fun GoogleAuthClient.newBrowserLogin(port: Int): BrowserLoginRequest {
    val verifier = Pkce.newVerifier()
    val state = Uuid.random().toHexString()
    val redirectUri = "http://127.0.0.1:$port/callback"
    val authUrl = buildAuthorizationUrl(redirectUri, Pkce.challenge(verifier), state)
    return BrowserLoginRequest(authUrl, redirectUri, state, verifier)
}

sealed interface LoginCallback {
    data class Code(val code: String) : LoginCallback
    data object Denied : LoginCallback
    data class Invalid(val reason: String) : LoginCallback
}

fun parseLoginCallback(query: Map<String, String>, expectedState: String): LoginCallback {
    if (query["state"] != expectedState) return LoginCallback.Invalid("state mismatch")
    val error = query["error"]
    if (error == "access_denied") return LoginCallback.Denied
    if (error != null) return LoginCallback.Invalid("authorization error: $error")
    val code = query["code"]
    if (code.isNullOrEmpty()) return LoginCallback.Invalid("missing code")
    return LoginCallback.Code(code)
}
