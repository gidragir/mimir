package dev.mimir.obsidian.ui

import dev.mimir.obsidian.App
import dev.mimir.obsidian.Modal
import dev.mimir.obsidian.Setting

class ConflictsModal(
    app: App,
    private val conflicts: List<String>,
    private val texts: PluginTexts,
) : Modal(app) {

    override fun onOpen() {
        val el = contentEl
        el.empty()

        el.createEl("h2", js("({ text: this.texts.conflictsModalTitle })"))
        el.createEl("p", js("({ text: this.texts.conflictsDesc })"))

        val listEl = el.createEl("ul")
        for (path in conflicts) {
            val item = listEl.createEl("li")
            item.textContent = path
        }

        Setting(el).addButton { btn ->
            btn.setButtonText("Close")
            btn.setCta()
            btn.onClick { close() }
        }
    }

    override fun onClose() {
        contentEl.empty()
    }
}
