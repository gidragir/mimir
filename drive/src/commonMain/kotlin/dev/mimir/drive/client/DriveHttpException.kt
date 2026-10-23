package dev.mimir.drive.client

import io.ktor.http.HttpStatusCode

open class DriveHttpException(
    val statusCode: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class DriveAuthException(
    statusCode: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : DriveHttpException(statusCode, message, cause)

class DriveRateLimitException(
    statusCode: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : DriveHttpException(statusCode, message, cause)
