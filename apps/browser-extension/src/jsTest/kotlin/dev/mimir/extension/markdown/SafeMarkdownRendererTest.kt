package dev.mimir.extension.markdown

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeMarkdownRendererTest {
    @Test
    fun rendersHeadingsListsCodeTablesAndWikilinksViaCreateElement() {
        var clickedWiki: String? = null
        val md = """
            # Заголовок 1
            Абзац с **жирным**, `кодом` и ссылкой [[Проекты/Архитектура|Архитектура]].

            - [x] Готово
            - [ ] В работе

            | Ключ | Значение |
            | --- | --- |
            | mode | sidePanel |

            ```kotlin
            val x = 42
            ```
        """.trimIndent()

        val root = SafeMarkdownRenderer.render(document, md, onWikilinkClick = { clickedWiki = it })
        assertEquals(" Заголовок 1".trim(), root.querySelector("h1")?.textContent)
        assertEquals("жирным", root.querySelector("strong")?.textContent)
        assertEquals("кодом", root.querySelector("p code")?.textContent)
        assertEquals(2, root.querySelectorAll("input[type='checkbox']").length)
        assertEquals("sidePanel", root.querySelector("table tbody td:last-child")?.textContent)
        assertTrue(root.querySelector("pre code")?.textContent?.contains("val x = 42") == true)

        val wikilink = root.querySelector("a.wikilink") as? HTMLElement
        assertNotNull(wikilink)
        assertEquals("Архитектура", wikilink.textContent)
        wikilink.click()
        assertEquals("Проекты/Архитектура|Архитектура", clickedWiki)
    }

    @Test
    fun blocksRawHtmlScriptsAndUnsafeUrlSchemes() {
        val malicious = """
            <script>window.pwned = true;</script>
            <img src=x onerror=alert(1)>
            [Плохая ссылка](javascript:alert(1))
            [Data ссылка](data:text/html,<script>alert(1)</script>)
            [Хорошая ссылка](https://example.com/docs)
        """.trimIndent()

        val root = SafeMarkdownRenderer.render(document, malicious)
        assertNull(root.querySelector("script"))
        assertNull(root.querySelector("img"))
        assertTrue(root.textContent?.contains("<script>window.pwned = true;</script>") == true)

        val anchors = root.querySelectorAll("a")
        assertEquals(1, anchors.length)
        val safeAnchor = anchors.item(0) as HTMLAnchorElement
        assertEquals("https://example.com/docs", safeAnchor.href)
    }

    @Test
    fun vbscriptLinksAndRawHtmlBecomePlainTextWithoutAnchors() {
        val root = SafeMarkdownRenderer.render(document, "[x](vbscript:msgbox(1)) <b onclick=1>t</b>")
        assertEquals(0, root.querySelectorAll("a").length)
        assertNull(root.querySelector("b"))
        assertTrue(root.textContent?.contains("<b onclick=1>t</b>") == true)
    }

    @Test
    fun rendersNestedListsBlockquotesAndFrontmatter() {
        val md = "---\ntags: [a]\n---\n- один\n  - вложенный\n- два\n\n> цитата\n"
        val root = SafeMarkdownRenderer.render(document, md)
        assertEquals("вложенный", root.querySelector("ul ul li")?.textContent)
        assertEquals("цитата", root.querySelector("blockquote")?.textContent?.trim())
        assertTrue(root.querySelector("pre.frontmatter")?.textContent?.contains("tags: [a]") == true)
    }

    @Test
    fun stripsMarkdownSyntaxToPlainText() {
        val plain = SafeMarkdownRenderer.stripMarkdownToPlainText(
            "# Заголовок\n**жирный** и `код`, [ссылка](https://e.com), [[Папка/Заметка|Алиас]]\n> цитата\n```kt\nval x = 1\n```",
        )
        assertEquals("Заголовок\nжирный и код, ссылка, Алиас\nцитата\nval x = 1", plain)
    }
}
