@file:JsModule("obsidian")

package dev.mimir.obsidian

import org.w3c.dom.HTMLElement

external class App

external open class Plugin {
    val app: App

    open fun onload()

    fun addStatusBarItem(): HTMLElement
}
