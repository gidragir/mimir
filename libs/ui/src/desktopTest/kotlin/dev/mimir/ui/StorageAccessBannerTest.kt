package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.screens.StatusBanners
import dev.mimir.ui.screens.SyncTexts
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StorageAccessBannerTest {

    @Test
    fun `banner with grant button when access revoked after setup`() = runComposeUiTest {
        val access = FakeStorageAccess(initialGranted = false)
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            vaultPath = "/storage/emulated/0/Documents/Vault",
        )

        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                StatusBanners(model, onOpenSetup = {})
            }
        }

        onNodeWithText(SyncTexts.STORAGE_ACCESS_REVOKED).assertExists()
        onNodeWithText(SyncTexts.GRANT).assertExists()
        onNodeWithText(SyncTexts.GRANT).performClick()
        assertTrue(access.requestAllFilesCalled)
    }

    @Test
    fun `sign in banner for SignInRequired error`() = runComposeUiTest {
        var openSetupCalled = false
        val model = FakeMimirUiModel(
            status = SyncStatus.Error("Session expired", kind = SyncErrorKind.SignInRequired),
            vaultPath = "/storage/emulated/0/Documents/Vault",
        )

        setContent {
            StatusBanners(model, onOpenSetup = { openSetupCalled = true })
        }

        onNodeWithText(SyncTexts.SIGN_IN_REQUIRED).assertExists()
        onNodeWithText(SyncTexts.SIGN_IN).assertExists()
        onNodeWithText(SyncTexts.SIGN_IN).performClick()
        assertTrue(openSetupCalled)
    }

    @Test
    fun `no banners on desktop (locals null)`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            vaultPath = "/home/user/vault",
        )

        setContent {
            StatusBanners(model, onOpenSetup = {})
        }

        onNodeWithText(SyncTexts.STORAGE_ACCESS_REVOKED).assertDoesNotExist()
        onNodeWithText(SyncTexts.GRANT).assertDoesNotExist()
        onNodeWithText(SyncTexts.SIGN_IN_REQUIRED).assertDoesNotExist()
    }

}
