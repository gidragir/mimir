package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.extension.platform.ExtensionPlatform

/** Двойник [ExtensionAuthHandler]: аккаунт берётся из `auth.account` платформы, сетевых вызовов нет. */
class FakeExtensionAuthHandler(
    private val platform: ExtensionPlatform,
    private val signInAccount: Account = Account(email = "signed-in@example.com", displayName = null),
) : ExtensionAuthHandler {
    override suspend fun currentAccount(): Account? = AccountCodec.load(platform)

    override suspend fun signInWithWebAuthFlow(): Account = signInAccount.also { AccountCodec.save(platform, it) }

    override suspend fun startDeviceCode(onPrompt: (DeviceCodeUiPrompt) -> Unit): Account {
        onPrompt(DeviceCodeUiPrompt(userCode = "ABCD-EFGH", verificationUrl = "https://www.google.com/device"))
        return signInAccount.also { AccountCodec.save(platform, it) }
    }

    override suspend fun signOut() {
        platform.removeLocalKey("auth.account")
    }
}
