@file:JsModule("obsidian")

package dev.mimir.obsidian

import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

external class DataAdapter {
    fun exists(path: String): Promise<Boolean>

    fun readBinary(path: String): Promise<ArrayBuffer>

    fun writeBinary(path: String, data: ArrayBuffer): Promise<Unit>

    fun appendBinary(path: String, data: ArrayBuffer): Promise<Unit>

    fun getResourcePath(path: String): String

    fun rename(from: String, to: String): Promise<Unit>

    fun remove(path: String): Promise<Unit>

    fun mkdir(path: String): Promise<Unit>

    fun stat(path: String): Promise<dynamic>

    fun list(path: String): Promise<dynamic>

    fun write(path: String, data: String): Promise<Unit>
}

external interface RequestUrlParam {
    var url: String
    var method: String?
    var headers: dynamic
    var body: dynamic
    var contentType: String?
    var `throw`: Boolean?
}

external interface RequestUrlResponse {
    val status: Int
    val headers: dynamic
    val arrayBuffer: ArrayBuffer
    val text: String
}

external fun requestUrl(request: dynamic): Promise<RequestUrlResponse>

external object Platform {
    val isMobile: Boolean
    val isIosApp: Boolean
    val isAndroidApp: Boolean
    val isDesktopApp: Boolean
}

external interface EventRef

external class TAbstractFile {
    val path: String
    val name: String
}

external class Vault {
    val adapter: DataAdapter
    val configDir: String

    fun on(name: String, callback: (dynamic) -> Unit): EventRef
    fun on(name: String, callback: (dynamic, dynamic) -> Unit): EventRef
}

external class Workspace {
    fun onLayoutReady(callback: () -> Unit)
}

external class App {
    val vault: Vault
    val workspace: Workspace
    val setting: dynamic

    fun loadLocalStorage(key: String): String?

    fun saveLocalStorage(key: String, value: String?)
}

external interface PluginManifest {
    val id: String
    val version: String
    val dir: String?
}

external class Notice(message: String, timeout: Number = definedExternally)

external open class Modal(app: App) {
    val app: App
    val contentEl: HTMLElement

    open fun onOpen()
    open fun onClose()
    fun open()
    fun close()
}

external open class Setting(containerEl: HTMLElement) {
    fun setName(name: String): Setting
    fun setDesc(desc: String): Setting
    fun setClass(cls: String): Setting
    fun addText(cb: (dynamic) -> Unit): Setting
    fun addDropdown(cb: (dynamic) -> Unit): Setting
    fun addToggle(cb: (dynamic) -> Unit): Setting
    fun addButton(cb: (dynamic) -> Unit): Setting
}

external open class PluginSettingTab(app: App, plugin: Plugin) {
    val app: App
    val plugin: Plugin
    val containerEl: HTMLElement

    open fun display()
    open fun hide()
}

external open class Plugin(app: App, manifest: PluginManifest) {
    val app: App
    val manifest: PluginManifest

    open fun onload()

    open fun onunload()

    fun addStatusBarItem(): HTMLElement

    fun addRibbonIcon(icon: String, title: String, callback: (dynamic) -> Unit): HTMLElement

    fun addCommand(command: dynamic): dynamic

    fun addSettingTab(tab: PluginSettingTab)

    fun registerEvent(eventRef: EventRef)

    fun registerInterval(id: Number): Number

    fun loadData(): Promise<dynamic>

    fun saveData(data: dynamic): Promise<Unit>
}
