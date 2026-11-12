package dev.mimir.obsidian.ui

import dev.mimir.obsidian.App
import dev.mimir.obsidian.Modal
import dev.mimir.obsidian.Notice
import dev.mimir.obsidian.Setting
import dev.mimir.obsidian.sync.DeviceSignIn
import dev.mimir.obsidian.sync.SignInState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DeviceCodeModal(
    app: App,
    private val signIn: DeviceSignIn,
    private val texts: PluginTexts,
    private val onComplete: (Boolean) -> Unit = {},
) : Modal(app) {

    private val modalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null

    override fun onOpen() {
        val el = contentEl
        el.empty()

        el.createEl("h2", js("({ text: this.texts.deviceCodeModalTitle })"))

        val statusDiv = el.createDiv()
        statusDiv.textContent = texts.deviceCodeModalTitle

        stateJob = modalScope.launch {
            signIn.state.collect { state ->
                renderState(statusDiv, state)
            }
        }
    }

    private fun renderState(container: dynamic, state: SignInState) {
        container.empty()
        when (state) {
            is SignInState.Idle, is SignInState.Requesting -> {
                container.createEl("p", js("({ text: 'Requesting device code from Google...' })"))
            }
            is SignInState.AwaitingUser -> {
                container.createEl("p", js("({ text: this.texts.deviceCodeInstructions(state.userCode, state.verificationUrl) })"))

                val codeEl = container.createEl("h1", js("({ text: state.userCode })"))
                codeEl.style.letterSpacing = "4px"
                codeEl.style.textAlign = "center"
                codeEl.style.padding = "12px"
                codeEl.style.userSelect = "all"

                Setting(container)
                    .addButton { btn ->
                        btn.setButtonText(texts.openVerificationUrlButton)
                        btn.setCta()
                        btn.onClick {
                            try {
                                kotlinx.browser.window.open(state.verificationUrl, "_blank")
                            } catch (_: Throwable) {}
                        }
                    }
                    .addButton { btn ->
                        btn.setButtonText(texts.copyCodeButton)
                        btn.onClick {
                            try {
                                kotlinx.browser.window.navigator.clipboard.writeText(state.userCode)
                                Notice(texts.codeCopiedNotice)
                            } catch (_: Throwable) {}
                        }
                    }
                    .addButton { btn ->
                        btn.setButtonText(texts.cancelSignInButton)
                        btn.onClick {
                            close()
                        }
                    }
            }
            is SignInState.Success -> {
                container.createEl("p", js("({ text: 'Sign-in successful!' })"))
                Notice(texts.signedInAs(state.email ?: "Google user"))
                onComplete(true)
                close()
            }
            is SignInState.Expired -> {
                container.createEl("p", js("({ text: 'Code expired. Please try signing in again.' })"))
                Setting(container).addButton { btn ->
                    btn.setButtonText(texts.cancelSignInButton)
                    btn.onClick { close() }
                }
            }
            is SignInState.Failed -> {
                container.createEl("p", js("({ text: 'Sign-in failed: ' + state.message })"))
                Setting(container).addButton { btn ->
                    btn.setButtonText(texts.cancelSignInButton)
                    btn.onClick { close() }
                }
            }
            is SignInState.Offline -> {
                container.createEl("p", js("({ text: texts.statusOffline })"))
                Setting(container).addButton { btn ->
                    btn.setButtonText(texts.cancelSignInButton)
                    btn.onClick { close() }
                }
            }
        }
    }

    override fun onClose() {
        stateJob?.cancel()
        modalScope.cancel()
        signIn.cancel()
        contentEl.empty()
    }
}
