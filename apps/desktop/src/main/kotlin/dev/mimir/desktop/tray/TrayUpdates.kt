package dev.mimir.desktop.tray

import dev.mimir.ui.model.UpdateUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Держит пункт «Доступно обновление vX.Y.Z» в меню [tray] в соответствии с [updates]: он есть, пока обновление
 * доступно или скачано, и пропадает при скрытии/установке. Нажатие открывает главное окно ([onOpen]):
 * там заметки о релизе и кнопки «Скачать и установить» / «Открыть страницу релиза» — а не молчаливая установка из меню.
 * Работает, пока жива вызывающая корутина.
 */
suspend fun bindUpdateItem(tray: Tray, updates: Flow<UpdateUiState>, onOpen: () -> Unit) {
    updates.map(::trayUpdateLabel).distinctUntilChanged().collect { label -> tray.setUpdate(label, onOpen) }
}
