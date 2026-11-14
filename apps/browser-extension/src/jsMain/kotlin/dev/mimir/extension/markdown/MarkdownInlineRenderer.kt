package dev.mimir.extension.markdown

import org.w3c.dom.Document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement

internal class MarkdownRenderContext(
    val document: Document,
    val onWikilinkClick: (String) -> Unit,
    val onCopyCode: (String) -> Unit,
) {
    fun el(tag: String, className: String? = null): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        if (className != null) element.className = className
        return element
    }
}

internal object MarkdownInlineRenderer {
    private val SAFE_URL_PREFIXES = listOf("https://", "http://", "mailto:")

    fun appendInline(ctx: MarkdownRenderContext, parent: HTMLElement, text: String) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                parent.appendChild(ctx.document.createTextNode(plain.toString()))
                plain.clear()
            }
        }
        fun wrapped(tag: String, inner: String): HTMLElement {
            flush()
            return ctx.el(tag).also {
                appendInline(ctx, it, inner)
                parent.appendChild(it)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    parent.appendChild(ctx.el("code").also { it.textContent = text.substring(i + 1, close) })
                    i = close + 1
                    continue
                }
            }
            if (text.startsWith("![[", i)) {
                val close = text.indexOf("]]", i + 3)
                if (close > i + 3) {
                    flush()
                    parent.appendChild(ctx.el("span", "embed").also { it.textContent = "📎 ${text.substring(i + 3, close)}" })
                    i = close + 2
                    continue
                }
            }
            if (text.startsWith("[[", i)) {
                val close = text.indexOf("]]", i + 2)
                if (close > i + 2) {
                    flush()
                    val target = text.substring(i + 2, close)
                    val label = if ('|' in target) target.substringAfter('|') else target
                    val link = ctx.el("a", "wikilink")
                    link.setAttribute("role", "link")
                    link.setAttribute("tabindex", "0")
                    link.textContent = label
                    link.onclick = { event ->
                        event.preventDefault()
                        ctx.onWikilinkClick(target)
                    }
                    parent.appendChild(link)
                    i = close + 2
                    continue
                }
            }
            if (c == '[') {
                val labelEnd = text.indexOf(']', i + 1)
                if (labelEnd > 0 && text.startsWith("](", labelEnd)) {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > 0) {
                        val label = text.substring(i + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd).trim()
                        if (SAFE_URL_PREFIXES.any { url.startsWith(it, ignoreCase = true) }) {
                            flush()
                            val a = ctx.el("a") as HTMLAnchorElement
                            a.href = url
                            a.target = "_blank"
                            a.rel = "noopener noreferrer"
                            appendInline(ctx, a, label)
                            parent.appendChild(a)
                        } else {
                            // небезопасная схема: только подпись, без href
                            plain.append(label)
                        }
                        i = urlEnd + 1
                        continue
                    }
                }
            }
            if (text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    wrapped("strong", text.substring(i + 2, close))
                    i = close + 2
                    continue
                }
            }
            if (text.startsWith("~~", i)) {
                val close = text.indexOf("~~", i + 2)
                if (close > i + 2) {
                    wrapped("del", text.substring(i + 2, close))
                    i = close + 2
                    continue
                }
            }
            if (text.startsWith("==", i)) {
                val close = text.indexOf("==", i + 2)
                if (close > i + 2) {
                    wrapped("mark", text.substring(i + 2, close))
                    i = close + 2
                    continue
                }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != ' ') {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1) {
                    wrapped("em", text.substring(i + 1, close))
                    i = close + 1
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }
}
