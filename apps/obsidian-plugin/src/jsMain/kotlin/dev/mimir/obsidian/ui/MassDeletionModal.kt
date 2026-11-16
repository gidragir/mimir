package dev.mimir.obsidian.ui

import dev.mimir.core.DeletionDecision
import dev.mimir.obsidian.App
import dev.mimir.obsidian.Modal
import dev.mimir.obsidian.Setting

/** Модальное окно подтверждения массового удаления файлов в облаке. */
class MassDeletionModal(
    app: App,
    private val count: Int,
    private val texts: PluginTexts,
    private val onDecision: (DeletionDecision) -> Unit,
) : Modal(app) {

    override fun onOpen() {
        val el = contentEl
        el.empty()

        el.createEl("h2", js("({ text: this.texts.confirmDeletionTitle(this.count) })"))
        el.createEl("p", js("({ text: this.texts.confirmDeletionHint })"))

        Setting(el)
            .addButton { btn ->
                btn.setButtonText(texts.confirmDeletionKeep)
                btn.setCta()
                btn.onClick {
                    close()
                    onDecision(DeletionDecision.Keep)
                }
            }
            .addButton { btn ->
                btn.setButtonText(texts.confirmDeletionDelete)
                btn.setWarning()
                btn.onClick {
                    close()
                    onDecision(DeletionDecision.Delete)
                }
            }
    }

    override fun onClose() {
        contentEl.empty()
    }
}
