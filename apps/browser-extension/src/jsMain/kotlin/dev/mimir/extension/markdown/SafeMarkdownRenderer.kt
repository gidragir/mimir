package dev.mimir.extension.markdown

import org.w3c.dom.Document
import org.w3c.dom.HTMLElement

/**
 * Markdown → DOM только через `createElement` / `createTextNode`: сырой HTML остаётся текстом,
 * ссылки допускают лишь `https:`, `http:` и `mailto:`. `innerHTML` не используется нигде.
 */
object SafeMarkdownRenderer {
    private val FRONTMATTER = Regex("^---\n([\\s\\S]*?)\n---(\n|$)")

    fun render(
        document: Document,
        markdown: String,
        onWikilinkClick: (String) -> Unit = {},
        onCopyCode: (String) -> Unit = {},
    ): HTMLElement {
        val ctx = MarkdownRenderContext(document, onWikilinkClick, onCopyCode)
        val root = ctx.el("div", "markdown-body")
        var text = markdown.replace("\r\n", "\n")
        FRONTMATTER.find(text)?.let { match ->
            root.appendChild(ctx.el("pre", "frontmatter").also { it.textContent = match.groupValues[1] })
            text = text.substring(match.range.last + 1)
        }
        MarkdownBlockRenderer.renderBlocks(ctx, root, text.split("\n"))
        return root
    }
}
