package dev.mimir.obsidian.fakes

import dev.mimir.obsidian.platform.RequestUrlFn
import dev.mimir.obsidian.platform.UrlRequest
import dev.mimir.obsidian.platform.UrlResponse

class FakeRequestUrl(
    var handler: suspend (UrlRequest) -> UrlResponse = { UrlResponse(200, emptyMap(), ByteArray(0)) },
) : RequestUrlFn {
    val requests = mutableListOf<UrlRequest>()
    val lastRequest: UrlRequest get() = requests.last()

    override suspend fun invoke(request: UrlRequest): UrlResponse {
        requests += request
        return handler(request)
    }
}
