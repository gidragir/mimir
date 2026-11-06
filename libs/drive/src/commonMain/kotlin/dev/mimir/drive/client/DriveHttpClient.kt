package dev.mimir.drive.client

import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.drive.auth.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock

/**
 * HTTP-клиент Drive: токены, повторы, сетевые ошибки.
 *
 * [httpClient] — внедрённый клиент с таймаутами Drive поверх него (движок общий): connect и socket (пауза между
 * пакетами), но без таймаута на весь запрос — вложение в сотни МБ на медленном канале передаётся минутами.
 * Плагин `HttpTimeout` обязателен: без него CIO применяет собственный `requestTimeout` (15 с) ко всему вызову
 * вместе с телом, и такой файл обрывался бы на каждой попытке. Таймаут на весь запрос, заданный во внедрённом
 * клиенте, здесь снимается.
 */
class DriveHttpClient(
    httpClient: HttpClient,
    private val tokenSource: AccessTokenSource,
    val clock: Clock = Clock.System,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val random: Random = Random.Default,
) {
    val httpClient: HttpClient = httpClient.config {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    constructor(
        httpClient: HttpClient,
        tokenStorage: TokenStorage,
        authClients: GoogleAuthClients,
        clock: Clock = Clock.System,
        retryPolicy: RetryPolicy = RetryPolicy(),
        random: Random = Random.Default,
    ) : this(httpClient, RefreshingTokenSource(tokenStorage, authClients, clock), clock, retryPolicy, random)

    private inline fun <T> mapTokenErrors(block: () -> T): T = try {
        block()
    } catch (e: DriveAuthException) {
        val code = e.statusCode.value
        if (code >= 500 || code == 429) {
            throw RemoteUnavailableException("Google sign-in service unavailable: ${e.message}", e)
        }
        throw RemoteAuthException(e.message.orEmpty(), e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.asRemoteUnavailableOrSelf()
    }

    suspend fun getValidToken(): String = mapTokenErrors { tokenSource.token() }

    suspend fun forceRefreshToken(): String = mapTokenErrors { tokenSource.refresh() }

    /**
     * Выполняет запрос с повторами: 401 — один принудительный refresh токена (не расходует попытки);
     * 429, 5xx и 403 rate limit — до [RetryPolicy.maxAttempts] запросов с паузами по [retryPolicy]
     * (`Retry-After` важнее backoff, но не больше [RetryPolicy.maxDelay]). Исчерпание — [RemoteUnavailableException].
     * Прочие ответы, включая нерейтлимитные 403, возвращаются вызывающему как есть.
     */
    suspend fun executeWithRetry(
        block: suspend (token: String) -> HttpResponse,
    ): HttpResponse {
        var token = getValidToken()
        var attempt = 0
        var refreshedOn401 = false

        while (true) {
            val response = try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.asRemoteUnavailableOrSelf()
            }
            if (response.status == HttpStatusCode.Unauthorized && !refreshedOn401) {
                refreshedOn401 = true
                token = forceRefreshToken()
                continue
            }

            val failure = retryableFailure(response) ?: return response
            attempt++
            if (attempt >= retryPolicy.maxAttempts) {
                throw RemoteUnavailableException(failure)
            }
            val retryAfter = parseRetryAfter(response.headers[HttpHeaders.RetryAfter], clock.now())
            delay(retryAfter?.let { minOf(it, retryPolicy.maxDelay) } ?: retryPolicy.backoff(attempt, random))
        }
    }

    /**
     * Те же правила повторов, но тело ответа не буферизуется: [consume] читает его прямо из соединения
     * и держит соединение, пока не вернётся. Для скачивания больших файлов.
     * Ответ, подлежащий повтору (401 до refresh, 429, 5xx), в [consume] не попадает.
     */
    suspend fun <T> executeStreaming(
        request: suspend (token: String) -> HttpStatement,
        consume: suspend (HttpResponse) -> T,
    ): T {
        var token = getValidToken()
        var attempt = 0
        var refreshedOn401 = false

        while (true) {
            var failure: String? = null
            var retryAfterHeader: String? = null
            var unauthorized = false
            val result = try {
                request(token).execute { response ->
                    when {
                        response.status == HttpStatusCode.Unauthorized && !refreshedOn401 -> {
                            unauthorized = true
                            null
                        }
                        else -> {
                            failure = retryableFailure(response)
                            if (failure != null) {
                                retryAfterHeader = response.headers[HttpHeaders.RetryAfter]
                                null
                            } else {
                                Holder(consume(response))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.asRemoteUnavailableOrSelf()
            }
            if (result != null) return result.value
            if (unauthorized) {
                refreshedOn401 = true
                token = forceRefreshToken()
                continue
            }
            attempt++
            if (attempt >= retryPolicy.maxAttempts) throw RemoteUnavailableException(failure!!)
            val retryAfter = parseRetryAfter(retryAfterHeader, clock.now())
            delay(retryAfter?.let { minOf(it, retryPolicy.maxDelay) } ?: retryPolicy.backoff(attempt, random))
        }
    }

    private class Holder<T>(val value: T)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SOCKET_TIMEOUT_MS = 30_000L
    }

    /** Описание повторяемого сбоя (для сообщения об исчерпании) или `null`, если ответ возвращается вызывающему. */
    private suspend fun retryableFailure(response: HttpResponse): String? {
        val status = response.status
        return when {
            status == HttpStatusCode.TooManyRequests -> "Google Drive rate limit exceeded (429 Too Many Requests)"
            status.value in 500..599 -> "Google Drive is unavailable ($status)"
            status == HttpStatusCode.Forbidden && isRateLimit403(response) ->
                "Google Drive rate limit exceeded (403 ${status.description})"
            else -> null
        }
    }
}

/**
 * Сетевой сбой (нет соединения, таймаут, не разрешился DNS) → [RemoteUnavailableException].
 * Ktor CIO на JVM при недоступном DNS бросает `java.nio.channels.UnresolvedAddressException`
 * (подкласс IllegalArgumentException); drive — common-код, поэтому она распознаётся по имени класса.
 * [CancellationException] сюда попадать не должна: её перехватывают раньше и пробрасывают как есть.
 */
private fun isNetworkFailure(e: Throwable): Boolean =
    e is IOException ||
        e is HttpRequestTimeoutException ||
        e::class.simpleName == "UnresolvedAddressException"

private fun Exception.asRemoteUnavailableOrSelf(): Exception =
    if (isNetworkFailure(this)) RemoteUnavailableException("Google Drive is unreachable: $message", this) else this

