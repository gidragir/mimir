package dev.mimir.drive

object GoogleAuthConfig {
    val scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "openid",
        "email",
        "profile",
    )
}
