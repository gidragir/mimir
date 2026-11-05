package dev.mimir.extension.ui

import dev.mimir.core.update.UpdateState
import dev.mimir.extension.update.ExtensionUpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** Строка «Доступна версия X» над основным экраном: пусто, пока обновления нет. Весь текст идёт через `textContent`. */
class UpdateBannerView(
    private val root: HTMLElement,
    private val controller: ExtensionUpdateController,
    private val scope: CoroutineScope,
) {
    private val dom = DomKit(root.ownerDocument!!)

    fun render(state: UpdateState) {
        root.textContent = ""
        if (state !is UpdateState.Available) return
        val row = dom.el("div", "banner update-banner", testId = "update-banner")
        row.appendChild(dom.el("span", "update-text", "Доступна версия ${state.release.version}", "update-text"))
        row.appendChild(dom.button("Открыть страницу релиза", "btn link", "update-open-btn") { scope.launch { controller.openReleasePage() } })
        row.appendChild(dom.button("Скрыть", "btn link", "update-dismiss-btn") { controller.dismiss() })
        root.appendChild(row)
    }
}
