package dev.mimir.obsidian.ui

import dev.mimir.core.sync.SyncStateStore
import dev.mimir.obsidian.App
import dev.mimir.obsidian.Notice
import dev.mimir.obsidian.Plugin
import dev.mimir.obsidian.PluginSettingTab
import dev.mimir.obsidian.Setting
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.PluginSettingsStore
import dev.mimir.obsidian.sync.DeviceSignIn
import dev.mimir.obsidian.sync.KeySetup
import dev.mimir.obsidian.sync.PluginSyncController
import dev.mimir.obsidian.sync.SyncAssembler
import dev.mimir.obsidian.sync.Trigger
import dev.mimir.obsidian.sync.deviceAuthClient
import dev.mimir.obsidian.sync.disconnectAccount
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.js.json

class MimirSettingTab(
    app: App,
    plugin: Plugin,
    private val settings: PluginSettingsStore,
    private val secrets: DeviceSecretStore,
    private val stateStore: SyncStateStore,
    private val assembler: SyncAssembler,
    private val controller: PluginSyncController,
    private val texts: PluginTexts,
    private val http: HttpClient,
) : PluginSettingTab(app, plugin) {

    private val tabScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun display() {
        tabScope.launch {
            render()
        }
    }

    private suspend fun render() {
        val container = containerEl
        container.empty()

        container.createEl("h2", json("text" to texts.settingsTitle))

        val currentTokens = secrets.load()
        val currentSettings = settings.get()
        val hasKey = secrets.loadKey() != null
        val accountEmail = secrets.loadEmail()

        // 1. Блок Аккаунт
        container.createEl("h3", json("text" to texts.accountSection))
        val accountSetting = Setting(container)
        if (currentTokens == null) {
            accountSetting.setName(texts.accountSection)
                .setDesc("Connect your Google account using Device Code authentication.")
                .addButton { btn ->
                    btn.setButtonText(texts.signInButton)
                    btn.setCta()
                    btn.onClick {
                        tabScope.launch {
                            val client = deviceAuthClient(http, currentSettings)
                            if (client == null) {
                                Notice("OAuth client ID not configured")
                                return@launch
                            }
                            val signIn = DeviceSignIn(client, secrets)
                            val modal = DeviceCodeModal(app, signIn, texts) { success ->
                                if (success) {
                                    tabScope.launch { render() }
                                }
                            }
                            modal.open()
                            signIn.run()
                        }
                    }
                }
        } else {
            accountSetting.setName(texts.accountSection)
                .setDesc(texts.signedInAs(accountEmail ?: "Google Drive user"))
                .addButton { btn ->
                    btn.setButtonText(texts.signOutButton)
                    btn.setWarning()
                    btn.onClick {
                        tabScope.launch {
                            val client = deviceAuthClient(http, currentSettings)
                            disconnectAccount(client, secrets, stateStore, assembler, controller)
                            render()
                        }
                    }
                }
        }

        // 2. Блок Папка Drive и пароль (активен, если выполнен вход)
        if (currentTokens != null) {
            container.createEl("h3", json("text" to texts.vaultSection))

            var rootFolderInput = currentSettings.rootFolder
            Setting(container)
                .setName(texts.rootFolderLabel)
                .setDesc(texts.rootFolderDesc)
                .addText { text ->
                    text.setPlaceholder("e.g. MyObsidianVault")
                    text.setValue(currentSettings.rootFolder)
                    text.onChange { value: String ->
                        rootFolderInput = value.trim()
                        tabScope.launch {
                            settings.update { it.copy(rootFolder = rootFolderInput) }
                        }
                    }
                }

            var passwordInput = ""
            var passwordStatusDiv: dynamic = null

            val pwdSetting = Setting(container)
                .setName(texts.passwordLabel)
                .setDesc(texts.passwordDesc)
                .addText { text ->
                    text.setPlaceholder(if (hasKey) "••••••••" else "Enter password")
                    text.inputEl.type = "password"
                    text.onChange { value: String ->
                        passwordInput = value
                    }
                }
                .addButton { btn ->
                    btn.setButtonText(texts.verifyPasswordButton)
                    btn.onClick {
                        if (passwordInput.isEmpty()) {
                            Notice("Please enter a password")
                            return@onClick
                        }
                        tabScope.launch {
                            passwordStatusDiv?.textContent = texts.verifyingPassword
                            val result = assembler.setupKey(passwordInput, null)
                            when (result) {
                                is KeySetup.Ok -> {
                                    val msg = if (result.firstDevice) texts.passwordValidFirstDevice else texts.passwordValidExisting
                                    passwordStatusDiv?.textContent = msg
                                    Notice(msg)
                                    render()
                                }
                                is KeySetup.WrongPassword -> {
                                    passwordStatusDiv?.textContent = result.message
                                    Notice(result.message)
                                }
                                is KeySetup.Failed -> {
                                    passwordStatusDiv?.textContent = result.message
                                    Notice(result.message)
                                }
                            }
                        }
                    }
                }

            passwordStatusDiv = container.createDiv()
            passwordStatusDiv.style.color = "var(--text-muted)"
            passwordStatusDiv.style.fontSize = "0.9em"
            passwordStatusDiv.style.marginBottom = "1em"
            if (hasKey) {
                passwordStatusDiv.textContent = texts.passwordValidExisting
            }
        }

        // 3. Блок Синхронизация (активен, если готова папка и есть ключ)
        if (currentTokens != null && hasKey && currentSettings.rootFolder.isNotEmpty()) {
            container.createEl("h3", json("text" to texts.syncSection))

            Setting(container)
                .setName(texts.parallelismLabel)
                .setDesc(texts.parallelismDesc)
                .addDropdown { dropdown ->
                    dropdown.addOption("1", "1")
                    dropdown.addOption("2", "2 (recommended)")
                    dropdown.addOption("3", "3")
                    dropdown.addOption("4", "4")
                    dropdown.setValue(currentSettings.parallelism.toString())
                    dropdown.onChange { value: String ->
                        val intVal = value.toIntOrNull() ?: 2
                        tabScope.launch {
                            settings.update { it.copy(parallelism = intVal) }
                        }
                    }
                }

            Setting(container)
                .setName(texts.forceSyncWithDaemonLabel)
                .setDesc(texts.forceSyncWithDaemonDesc)
                .addToggle { toggle ->
                    toggle.setValue(currentSettings.forceSyncWithDaemon)
                    toggle.onChange { value: Boolean ->
                        tabScope.launch {
                            settings.update { it.copy(forceSyncWithDaemon = value) }
                        }
                    }
                }

            Setting(container)
                .setName("Sync")
                .setDesc("Trigger an immediate foreground synchronization.")
                .addButton { btn ->
                    btn.setButtonText(texts.syncNowButton)
                    btn.setCta()
                    btn.onClick {
                        controller.trigger(Trigger.Manual)
                        Notice("Mimir sync started")
                    }
                }
        }
    }
}
