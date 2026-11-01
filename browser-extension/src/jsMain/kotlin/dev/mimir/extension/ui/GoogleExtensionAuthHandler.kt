package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.LoginCallback
import dev.mimir.drive.auth.Pkce
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.auth.parseLoginCallback
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.extension.platform.ExtensionPlatform
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Вход в Google: PKCE через `chrome.identity.launchWebAuthFlow` или резервный Device Code Flow. */
class GoogleExtensionAuthHandler(
    private val platform: ExtensionPlatform,
    private val clients: GoogleAuthClients,
    private val tokenStorage: TokenStorage,
    private val clock: Clock = Clock.System,
) : ExtensionAuthHandler {

    override suspend fun currentAccount(): Account? {
        if (tokenStorage.load() == null) return null
        return AccountCodec.load(platform)
    }

    override suspend fun signInWithWebAuthFlow(): Account {
        val client = clients.desktop ?: clients.device ?: error("Не задан Google Client ID")
        val verifier = Pkce.newVerifier()
        val state = Uuid.random().toHexString()
        val redirectUri = platform.getRedirectUrl("oauth2")
        val authUrl = client.buildAuthorizationUrl(redirectUri, Pkce.challenge(verifier), state)
        val finalUrl = platform.launchWebAuthFlow(authUrl, interactive = true)
        val code = when (val callback = parseLoginCallback(parseQuery(finalUrl), state)) {
            is LoginCallback.Code -> callback.code
            LoginCallback.Denied -> error("Доступ отклонён")
            is LoginCallback.Invalid -> error("Ошибка входа: ${callback.reason}")
        }
        val tokens = client.exchangeAuthorizationCode(code, verifier, redirectUri)
        tokenStorage.save(tokens)
        return saveAccount(client, tokens.accessToken)
    }

    override suspend fun startDeviceCode(onPrompt: (DeviceCodeUiPrompt) -> Unit): Account {
        val client = clients.device ?: clients.desktop ?: error("Не задан Google Client ID")
        val response = client.requestDeviceCode()
        onPrompt(DeviceCodeUiPrompt(response.user_code, response.verification_url))
        val deadline = clock.now().epochSeconds + response.expires_in
        while (clock.now().epochSeconds < deadline) {
            delay(response.interval * 1000)
            val tokens = try {
                client.pollDeviceToken(response.device_code)
            } catch (e: DriveAuthException) {
                val message = e.message.orEmpty()
                if ("authorization_pending" in message || "slow_down" in message) continue
                throw e
            }
            tokenStorage.save(tokens)
            return saveAccount(client, tokens.accessToken)
        }
        error("Время действия кода истекло")
    }

    override suspend fun signOut() {
        tokenStorage.clear()
        platform.removeLocalKey(AccountCodec.KEY)
    }

    private suspend fun saveAccount(client: GoogleAuthClient, accessToken: String): Account =
        client.fetchAccount(accessToken).also { AccountCodec.save(platform, it) }

    private fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        return query.split('&').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=').decodeURLQueryComponent() to it.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true)
        }
    }
}
