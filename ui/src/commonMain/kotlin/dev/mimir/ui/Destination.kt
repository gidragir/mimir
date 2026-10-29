package dev.mimir.ui

enum class Destination {
    Status,
    Conflicts,
    Login;

    companion object {
        fun fromString(value: String?): Destination = when (value?.lowercase()) {
            "conflicts" -> Conflicts
            "login" -> Login
            else -> Status
        }
    }
}
