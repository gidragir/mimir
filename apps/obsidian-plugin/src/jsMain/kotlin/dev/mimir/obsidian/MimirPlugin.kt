package dev.mimir.obsidian

import dev.mimir.core.fs.EchoFilter
import dev.mimir.obsidian.daemon.DaemonProbe
import dev.mimir.obsidian.diag.runDiagnostics
import dev.mimir.obsidian.diag.showDiagnostics
import dev.mimir.obsidian.net.ObsidianHttpEngine
import dev.mimir.obsidian.platform.AdapterVaultFs
import dev.mimir.obsidian.platform.AppLocalStorage
import dev.mimir.obsidian.platform.FetchResourceStream
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.platform.obsidianRequestUrl
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.ObsidianSyncStateStore
import dev.mimir.obsidian.state.PluginSettingsStore
import dev.mimir.obsidian.sync.PluginState
import dev.mimir.obsidian.sync.PluginSyncController
import dev.mimir.obsidian.sync.SyncAssembler
import dev.mimir.obsidian.sync.Trigger
import dev.mimir.obsidian.ui.ConflictsModal
import dev.mimir.obsidian.ui.MimirSettingTab
import dev.mimir.obsidian.ui.PluginTexts
import dev.mimir.obsidian.ui.statusLabel
import dev.mimir.obsidian.update.PluginUpdater
import dev.mimir.obsidian.vault.ObsidianLocalVault
import io.ktor.client.HttpClient
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.js.json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
@JsName("default")
class MimirPlugin(app: App, manifest: PluginManifest) : Plugin(app, manifest) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: PluginSyncController? = null

    override fun onload() {
        val texts = PluginTexts.detect()
        val http = HttpClient(ObsidianHttpEngine(obsidianRequestUrl))

        val storage = AppLocalStorage(app)
        val secrets = DeviceSecretStore(storage)
        val settingsStore = PluginSettingsStore(
            load = { loadData() },
            save = { saveData(it) },
        )

        val vaultFs = AdapterVaultFs(app.vault.adapter)
        val streamFn = FetchResourceStream(app.vault.adapter)
        val vaultEventsFlow = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 100)

        // Регистрация слушателей событий хранилища
        try {
            registerEvent(app.vault.on("create") { file: dynamic ->
                val path = file?.path as? String ?: return@on
                vaultEventsFlow.tryEmit(VaultEvent.Upsert(path))
            })
            registerEvent(app.vault.on("modify") { file: dynamic ->
                val path = file?.path as? String ?: return@on
                vaultEventsFlow.tryEmit(VaultEvent.Upsert(path))
            })
            registerEvent(app.vault.on("delete") { file: dynamic ->
                val path = file?.path as? String ?: return@on
                vaultEventsFlow.tryEmit(VaultEvent.Removed(path))
            })
            registerEvent(app.vault.on("rename") { file: dynamic, oldPath: dynamic ->
                val newPath = file?.path as? String ?: return@on
                val old = oldPath as? String ?: return@on
                vaultEventsFlow.tryEmit(VaultEvent.Renamed(old, newPath))
            })
        } catch (_: Throwable) {}

        val configDir = app.vault.configDir
        val localVault = ObsidianLocalVault(
            fs = vaultFs,
            resources = streamFn,
            configDir = configDir,
            events = vaultEventsFlow,
            echo = EchoFilter(ttl = 5.seconds, clock = Clock.System),
        )
        val stateStore = ObsidianSyncStateStore(vaultFs, configDir)
        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settingsStore,
            vault = localVault,
            stateStore = stateStore,
        )

        val vaultBasePath = try {
            app.vault.adapter.getResourcePath("").replace("app://local", "").replace("file://", "")
        } catch (_: Throwable) {
            null
        }

        val probe = DaemonProbe(
            request = obsidianRequestUrl,
            port = 47821,
            isDesktop = Platform.isDesktopApp,
            vaultBasePath = vaultBasePath,
        )

        val syncController = PluginSyncController(
            scope = scope,
            assembler = assembler,
            probe = probe,
            secrets = secrets,
            settings = settingsStore,
            vaultEvents = vaultEventsFlow,
        )
        controller = syncController

        // Статус-бар (на desktop)
        val statusBarItem = try {
            addStatusBarItem()
        } catch (_: Throwable) {
            null
        }

        if (statusBarItem != null) {
            statusBarItem.textContent = statusLabel(PluginState.NotConnected, texts, Clock.System.now())
            scope.launch {
                syncController.state.collect { state ->
                    statusBarItem.textContent = statusLabel(state, texts, Clock.System.now())
                }
            }
            try {
                val intervalId = window.setInterval({
                    statusBarItem.textContent = statusLabel(syncController.state.value, texts, Clock.System.now())
                }, 30000)
                registerInterval(intervalId)
            } catch (_: Throwable) {}
        }

        // Ribbon icon (быстрая синхронизация)
        try {
            addRibbonIcon("refresh-cw", "Mimir Sync") {
                syncController.trigger(Trigger.Manual)
                Notice("Mimir sync started")
            }
        } catch (_: Throwable) {}

        // Settings tab
        try {
            addSettingTab(
                MimirSettingTab(
                    app = app,
                    plugin = this,
                    settings = settingsStore,
                    secrets = secrets,
                    assembler = assembler,
                    controller = syncController,
                    texts = texts,
                    http = http,
                ),
            )
        } catch (_: Throwable) {}

        // Commands
        addCommand(
            json(
                "id" to "mimir-sync-now",
                "name" to "Sync now",
                "callback" to {
                    syncController.trigger(Trigger.Manual)
                    Notice("Mimir sync started")
                },
            ),
        )

        addCommand(
            json(
                "id" to "mimir-show-conflicts",
                "name" to "Show sync conflicts",
                "callback" to {
                    val state = syncController.state.value
                    if (state is PluginState.UpToDate && state.conflicts.isNotEmpty()) {
                        ConflictsModal(app, state.conflicts, texts).open()
                    } else {
                        Notice("No conflicts found")
                    }
                },
            ),
        )

        addCommand(
            json(
                "id" to "mimir-diagnostics",
                "name" to "Diagnostics",
                "callback" to {
                    scope.launch {
                        Notice("Mimir: diagnostics started", 5000)
                        try {
                            runDiagnostics(app) { showDiagnostics(app, it) }
                        } catch (e: Throwable) {
                            showDiagnostics(app, listOf("FAIL ${e.message}"))
                        }
                    }
                },
            ),
        )

        // Visibility listener
        try {
            document.addEventListener("visibilitychange", {
                val isHidden = js("document.hidden").unsafeCast<Boolean>()
                syncController.onVisibilityChanged(!isHidden)
            })
        } catch (_: Throwable) {}

        // Notice при первом старте, если не настроено
        try {
            app.workspace.onLayoutReady {
                scope.launch {
                    if (!assembler.isReady()) {
                        Notice(texts.pleaseConfigureNotice)
                    }
                }
            }
        } catch (_: Throwable) {}

        // Updater
        try {
            PluginUpdater(this, scope).start()
        } catch (e: Throwable) {
            console.warn("Mimir: updater disabled: ${e.message}")
        }

        // Start sync controller
        syncController.start()
    }

    override fun onunload() {
        controller?.stop()
        controller = null
        scope.cancel()
    }
}
