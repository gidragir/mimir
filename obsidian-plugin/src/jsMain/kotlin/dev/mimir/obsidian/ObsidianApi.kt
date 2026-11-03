@file:JsModule("obsidian")

package dev.mimir.obsidian

import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

external class DataAdapter {
    fun exists(path: String): Promise<Boolean>

    fun readBinary(path: String): Promise<ArrayBuffer>

    fun writeBinary(path: String, data: ArrayBuffer): Promise<Unit>
}

external class Vault {
    val adapter: DataAdapter
    val configDir: String
}

external class App {
    val vault: Vault
}

external interface PluginManifest {
    val id: String
    val version: String
    val dir: String?
}

external class Notice(message: String, timeout: Number = definedExternally)

external open class Plugin {
    val app: App
    val manifest: PluginManifest

    open fun onload()

    open fun onunload()

    fun addStatusBarItem(): HTMLElement

    fun addCommand(command: dynamic): dynamic

    fun loadData(): Promise<dynamic>

    fun saveData(data: dynamic): Promise<Unit>
}
