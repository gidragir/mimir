package dev.mimir.drive.auth

import dev.mimir.core.Account
import dev.mimir.drive.GoogleAuthConfig
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import dev.mimir.drive.api.GoogleTokenResponse
import dev.mimir.drive.api.GoogleUserInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlin.time.Clock

class GoogleAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String? = null,
    private val clock: Clock = Clock.System,
) {
    fun buildAuthorizationUrl(redirectUri: String, codeChallenge: String, state: String): String {
        val scopeParam = GoogleAuthConfig.scopes.joinToString("%20")
        val secretPart = clientSecret?.let { "&client_secret=$it" } ?: ""
        return "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=$clientId" +
            "&redirect_uri=$redirectUri" +
            "&response_type=code" +
            "&scope=$scopeParam" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&state=$state" +
            "&access_type=offline" +
            "&prompt=consent" +
            secretPart
    }

    suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String, redirectUri: String): OAuthTokens {
        val form = parameters {
            append("client_id", clientId)
            clientSecret?.let { append("client_secret", it) }
            append("code", code)
            append("code_verifier", codeVerifier)
            append("grant_type", "authorization_code")
            append("redirect_uri", redirectUri)
        }
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = form,
        ).body<GoogleTokenResponse>()

        return toOAuthTokens(response, null)
    }

    suspend fun refreshTokens(refreshToken: String): OAuthTokens {
        val form = parameters {
            append("client_id", clientId)
            clientSecret?.let { append("client_secret", it) }
            append("refresh_token", refreshToken)
            append("grant_type", "refresh_token")
        }
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = form,
        ).body<GoogleTokenResponse>()

        return toOAuthTokens(response, refreshToken)
    }

    suspend fun requestDeviceCode(): GoogleDeviceCodeResponse {
        val form = parameters {
            append("client_id", clientId)
            append("scope", GoogleAuthConfig.scopes.joinToString(" "))
        }
        return httpClient.submitForm(
            url = "https://oauth2.googleapis.com/device/code",
            formParameters = form,
        ).body<GoogleDeviceCodeResponse>()
    }

    suspend fun pollDeviceToken(deviceCode: String): OAuthTokens {
        val form = parameters {
            append("client_id", clientId)
            clientSecret?.let { append("client_secret", it) }
            append("device_code", deviceCode)
            append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = form,
        ).body<GoogleTokenResponse>()

        return toOAuthTokens(response, null)
    }

    suspend fun fetchAccount(accessToken: String): Account {
        val info = httpClient.get("https://www.googleapis.com/oauth2/v3/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }.body<GoogleUserInfo>()

        return Account(email = info.email ?: "", displayName = info.name)
    }

    private fun toOAuthTokens(response: GoogleTokenResponse, fallbackRefresh: String?): OAuthTokens {
        val effectiveRefresh = response.refresh_token ?: fallbackRefresh
        val expiresAt = clock.now().epochSeconds + response.expires_in
        return OAuthTokens(
            accessToken = response.access_token,
            refreshToken = effectiveRefresh,
            expiresAtEpochSeconds = expiresAt,
            scope = response.scope ?: "",
            tokenType = response.token_type,
        )
    }
}
