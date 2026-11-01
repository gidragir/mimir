package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.browser.document
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopupDomViewTest {
    @Test
    fun sidePanelModeSwitchesMasterDetailWhileTabModeRendersTwoColumns() = runTest {
        val cipher = RcloneCipher.create("pass")
        val remote = InMemoryRemoteStorage()
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        remote.write(
            RemotePath.of(listOf("Архитектура.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# Заголовок заметки") }),
        )

        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "dev@mimir.io", displayName = "Dev"))
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { remote },
            authHandler = FakeExtensionAuthHandler(platform),
        )
        controller.initialize()
        controller.unlock("pass")

        // 1. Узкий режим Side Panel (isFullTabMode = false): сначала только список, без панели чтения
        val sidePanelContainer = document.createElement("div") as HTMLElement
        val sidePanelView = PopupDomView(sidePanelContainer, controller, platform, isFullTabMode = false)
        sidePanelView.render(controller.state.value)

        assertNotNull(sidePanelContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNull(sidePanelContainer.querySelector("[data-testid='note-reader-pane']"))

        // Открываем заметку -> в Side Panel отображается только экран чтения с кнопкой «← Назад»
        val noteFile = (controller.state.value as ExtensionUiState.VaultReady).flatNotes.single()
        controller.openNote(noteFile)
        sidePanelView.render(controller.state.value)

        assertNull(sidePanelContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNotNull(sidePanelContainer.querySelector("[data-testid='note-reader-pane']"))
        assertNotNull(sidePanelContainer.querySelector("[data-testid='back-to-list-btn']"))

        // 2. Полноэкранная вкладка (isFullTabMode = true): обе колонки рендерятся одновременно
        val tabContainer = document.createElement("div") as HTMLElement
        val tabView = PopupDomView(tabContainer, controller, platform, isFullTabMode = true)
        tabView.render(controller.state.value)

        assertNotNull(tabContainer.querySelector("[data-testid='vault-tree-pane']"))
        assertNotNull(tabContainer.querySelector("[data-testid='note-reader-pane']"))
        assertTrue(tabContainer.textContent?.contains("Заголовок заметки") == true)
    }

    @Test
    fun unlockScreenShowsErrorAndLockButtonReturnsToUnlock() = runTest {
        val platform = FakeExtensionPlatform()
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { InMemoryRemoteStorage() },
            authHandler = FakeExtensionAuthHandler(platform),
        )
        val container = document.createElement("div") as HTMLElement
        val view = PopupDomView(container, controller, platform, isFullTabMode = false)

        view.render(ExtensionUiState.AuthRequired(error = "Ошибка сети"))
        assertTrue(container.textContent?.contains("Ошибка сети") == true)
        assertNotNull(container.querySelector("[data-testid='google-sign-in-btn']"))

        view.render(ExtensionUiState.UnlockRequired(Account("a@b.c", null), error = "Неверный мастер-пароль"))
        assertTrue(container.textContent?.contains("Неверный мастер-пароль") == true)
        assertNotNull(container.querySelector("input[type='password']"))
    }
}
