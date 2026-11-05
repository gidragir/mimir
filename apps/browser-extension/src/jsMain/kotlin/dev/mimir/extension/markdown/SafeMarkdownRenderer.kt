package dev.mimir.extension.markdown

import org.w3c.dom.Document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Markdown → DOM только через `createElement` / `createTextNode`: сырой HTML остаётся текстом,
 * ссылки допускают лишь `https:`, `http:` и `mailto:`. `innerHTML` не используется нигде.
 */
object SafeMarkdownRenderer {
    private class Ctx(val document: Document, val onWikilinkClick: (String) -> Unit, val onCopyCode: (String) -> Unit)

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[( |x|X)\\]\\s+(.*)$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val FRONTMATTER = Regex("^---\n([\\s\\S]*?)\n---(\n|$)")
    private val SAFE_URL_PREFIXES = listOf("https://", "http://", "mailto:")

    fun render(
        document: Document,
        markdown: String,
        onWikilinkClick: (String) -> Unit = {},
        onCopyCode: (String) -> Unit = {},
    ): HTMLElement {
        val ctx = Ctx(document, onWikilinkClick, onCopyCode)
        val root = ctx.el("div", "markdown-body")
        var text = markdown.replace("\r\n", "\n")
        FRONTMATTER.find(text)?.let { match ->
            root.appendChild(ctx.el("pre", "frontmatter").also { it.textContent = match.groupValues[1] })
            text = text.substring(match.range.last + 1)
        }
        renderBlocks(ctx, root, text.split("\n"))
        return root
    }

    fun stripMarkdownToPlainText(markdown: String): String {
        var text = markdown.replace("\r\n", "\n")
        FRONTMATTER.find(text)?.let { text = text.substring(it.range.last + 1) }
        var inFence = false
        val out = mutableListOf<String>()
        for (raw in text.split("\n")) {
            if (raw.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                out += raw
                continue
            }
            if (HR.matches(raw)) continue
            var line = raw.replace(Regex("^#{1,6}\\s+"), "")
            while (line.trimStart().startsWith(">")) line = line.trimStart().removePrefix(">").removePrefix(" ")
            line = line
                .replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]"), "$2")
                .replace(Regex("\\[\\[([^\\]]*)\\]\\]"), "$1")
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("~~(.+?)~~"), "$1")
                .replace(Regex("==(.+?)=="), "$1")
                .replace(Regex("\\*(.+?)\\*"), "$1")
                .replace(Regex("`([^`]*)`"), "$1")
            out += line
        }
        return out.joinToString("\n").trim()
    }

    private fun Ctx.el(tag: String, className: String? = null): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        if (className != null) element.className = className
        return element
    }

    private fun renderBlocks(ctx: Ctx, parent: HTMLElement, lines: List<String>) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                line.isBlank() -> i++
                trimmed.startsWith("```") -> i = renderFence(ctx, parent, lines, i)
                HEADING.matches(line.trimEnd()) && trimmed.startsWith("#") -> {
                    val m = HEADING.find(line.trimEnd())!!
                    val h = ctx.el("h${m.groupValues[1].length}")
                    appendInline(ctx, h, m.groupValues[2])
                    parent.appendChild(h)
                    i++
                }
                HR.matches(line) -> {
                    parent.appendChild(ctx.el("hr"))
                    i++
                }
                trimmed.startsWith(">") -> {
                    val inner = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    val quote = ctx.el("blockquote")
                    renderBlocks(ctx, quote, inner)
                    parent.appendChild(quote)
                }
                isTableStart(lines, i) -> i = renderTable(ctx, parent, lines, i)
                LIST_ITEM.matches(line) -> i = renderList(ctx, parent, lines, i, indentOf(line))
                else -> i = renderParagraph(ctx, parent, lines, i)
            }
        }
    }

    private fun renderFence(ctx: Ctx, parent: HTMLElement, lines: List<String>, start: Int): Int {
        val lang = lines[start].trimStart().removePrefix("```").trim()
        var i = start + 1
        val code = mutableListOf<String>()
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
            code += lines[i]
            i++
        }
        val source = code.joinToString("\n")
        val wrapper = ctx.el("div", "code-block")
        val button = ctx.el("button", "copy-code")
        button.setAttribute("type", "button")
        button.textContent = "Копировать"
        button.onclick = { ctx.onCopyCode(source) }
        val pre = ctx.el("pre")
        val codeEl = ctx.el("code", if (lang.isEmpty()) null else "language-${lang.filter { it.isLetterOrDigit() || it == '-' }}")
        codeEl.textContent = source
        pre.appendChild(codeEl)
        wrapper.appendChild(button)
        wrapper.appendChild(pre)
        parent.appendChild(wrapper)
        return minOf(i + 1, lines.size)
    }

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        val trimmed = line.trimStart()
        return line.isBlank() || trimmed.startsWith("```") || trimmed.startsWith(">") ||
            (trimmed.startsWith("#") && HEADING.matches(line.trimEnd())) || HR.matches(line) ||
            LIST_ITEM.matches(line) || isTableStart(lines, i)
    }

    private fun renderParagraph(ctx: Ctx, parent: HTMLElement, lines: List<String>, start: Int): Int {
        val p = ctx.el("p")
        var i = start
        while (i < lines.size && (i == start || !startsBlock(lines, i))) {
            if (i > start) p.appendChild(ctx.el("br"))
            appendInline(ctx, p, lines[i].trim())
            i++
        }
        parent.appendChild(p)
        return i
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }

    private fun renderList(ctx: Ctx, parent: HTMLElement, lines: List<String>, start: Int, baseIndent: Int): Int {
        val first = LIST_ITEM.find(lines[start])!!
        val ordered = first.groupValues[2].first().isDigit()
        val list = ctx.el(if (ordered) "ol" else "ul")
        if (ordered) first.groupValues[2].takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it != 1 }?.let { list.setAttribute("start", it.toString()) }
        var i = start
        while (i < lines.size) {
            var line = lines[i]
            if (line.isBlank()) {
                val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                val nextMatch = LIST_ITEM.find(lines[next]) ?: break
                if (indentOf(lines[next]) < baseIndent) break
                if (nextMatch.groupValues[2].first().isDigit() != ordered && indentOf(lines[next]) == baseIndent) break
                i = next
                line = lines[i]
            }
            val m = LIST_ITEM.find(line) ?: break
            val indent = indentOf(line)
            if (indent < baseIndent) break
            if (indent > baseIndent) break
            if (m.groupValues[2].first().isDigit() != ordered) break
            val li = ctx.el("li")
            val task = TASK.find(m.groupValues[3])
            if (task != null) {
                val box = ctx.document.createElement("input") as HTMLInputElement
                box.type = "checkbox"
                box.disabled = true
                box.checked = task.groupValues[1] != " "
                li.appendChild(box)
                appendInline(ctx, li, task.groupValues[2])
            } else {
                appendInline(ctx, li, m.groupValues[3])
            }
            i++
            while (i < lines.size) {
                if (!LIST_ITEM.matches(lines[i])) break
                val nestedIndent = indentOf(lines[i])
                if (nestedIndent <= baseIndent) break
                i = renderList(ctx, li, lines, i, nestedIndent)
            }
            list.appendChild(li)
        }
        parent.appendChild(list)
        return i
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        i + 1 < lines.size && lines[i].contains('|') && TABLE_SEPARATOR.matches(lines[i + 1]) &&
            lines[i + 1].contains('-') && (lines[i].contains('|') || lines[i + 1].contains('|'))

    private fun splitRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val body = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
                current.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    private fun renderTable(ctx: Ctx, parent: HTMLElement, lines: List<String>, start: Int): Int {
        val wrap = ctx.el("div", "table-wrap")
        val table = ctx.el("table")
        val thead = ctx.el("thead")
        val headRow = ctx.el("tr")
        splitRow(lines[start]).forEach { cell ->
            val th = ctx.el("th")
            appendInline(ctx, th, cell)
            headRow.appendChild(th)
        }
        thead.appendChild(headRow)
        val tbody = ctx.el("tbody")
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            val tr = ctx.el("tr")
            splitRow(lines[i]).forEach { cell ->
                val td = ctx.el("td")
                appendInline(ctx, td, cell)
                tr.appendChild(td)
            }
            tbody.appendChild(tr)
            i++
        }
        table.appendChild(thead)
        table.appendChild(tbody)
        wrap.appendChild(table)
        parent.appendChild(wrap)
        return i
    }

    private fun appendInline(ctx: Ctx, parent: HTMLElement, text: String) {
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
