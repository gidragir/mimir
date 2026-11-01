package dev.mimir.extension.ui

import org.w3c.dom.Document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** Мелкие фабрики DOM-узлов; весь текст попадает в `textContent`, `innerHTML` не используется. */
internal class DomKit(val document: Document) {
    fun el(tag: String, className: String? = null, text: String? = null, testId: String? = null): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        if (className != null) element.className = className
        if (text != null) element.textContent = text
        if (testId != null) element.setAttribute("data-testid", testId)
        return element
    }

    fun button(label: String, className: String = "btn", testId: String? = null, title: String? = null, onClick: () -> Unit): HTMLButtonElement {
        val b = el("button", className, label, testId) as HTMLButtonElement
        b.type = "button"
        if (title != null) {
            b.title = title
            b.setAttribute("aria-label", title)
        }
        b.onclick = { onClick() }
        return b
    }

    fun input(type: String, placeholder: String? = null, testId: String? = null): HTMLInputElement {
        val i = el("input", null, null, testId) as HTMLInputElement
        i.type = type
        if (placeholder != null) i.placeholder = placeholder
        return i
    }
}
