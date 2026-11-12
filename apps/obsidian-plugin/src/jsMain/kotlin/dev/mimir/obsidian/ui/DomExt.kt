package dev.mimir.obsidian.ui

import org.w3c.dom.HTMLElement

fun HTMLElement.empty() {
    this.asDynamic().empty()
}

fun HTMLElement.createEl(tag: String, o: dynamic = null): HTMLElement {
    return this.asDynamic().createEl(tag, o).unsafeCast<HTMLElement>()
}

fun HTMLElement.createDiv(o: dynamic = null): HTMLElement {
    return this.asDynamic().createDiv(o).unsafeCast<HTMLElement>()
}
