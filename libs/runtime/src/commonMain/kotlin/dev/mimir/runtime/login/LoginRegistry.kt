package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod

/** Доступные на платформе способы входа; точка входа регистрирует только те, что у неё настроены. */
class LoginRegistry(providers: List<LoginProvider>) {
    private val byMethod: Map<DaemonLoginMethod, LoginProvider> = providers.associateBy { it.method }

    init {
        require(byMethod.size == providers.size) { "Duplicate login providers: ${providers.map { it.method }}" }
    }

    fun methods(): Set<DaemonLoginMethod> = byMethod.keys

    operator fun get(method: DaemonLoginMethod): LoginProvider? = byMethod[method]

    fun close() = byMethod.values.forEach { it.close() }
}

/** Почему способ входа недоступен: на этой платформе не зарегистрирован его провайдер. */
fun DaemonLoginMethod.notConfiguredMessage(): String = when (this) {
    DaemonLoginMethod.Browser -> "Google desktop client is not configured"
    DaemonLoginMethod.DeviceCode -> "Google device client is not configured"
    DaemonLoginMethod.SystemAccount -> "System account sign-in is not available"
}
