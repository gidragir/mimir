package dev.mimir.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.SyncStatus
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirProgressBar
import dev.mimir.ui.components.MimirStatusBadge
import dev.mimir.ui.components.MimirTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MimirComponentsTest {

    @Test
    fun `mimir button renders text and triggers click`() = runComposeUiTest {
        var clicked = false
        setContent {
            MimirTheme {
                MimirButton(
                    text = "Сохранить",
                    onClick = { clicked = true },
                )
            }
        }

        onNodeWithText("Сохранить").assertIsDisplayed()
        onNodeWithText("Сохранить").assertIsEnabled()
        onNodeWithText("Сохранить").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `mimir button disabled state prevents click`() = runComposeUiTest {
        var clicked = false
        setContent {
            MimirTheme {
                MimirButton(
                    text = "Заблокировано",
                    onClick = { clicked = true },
                    enabled = false,
                )
            }
        }

        onNodeWithText("Заблокировано").assertIsDisplayed()
        onNodeWithText("Заблокировано").assertIsNotEnabled()
        onNodeWithText("Заблокировано").performClick()
        assertFalse(clicked)
    }

    @Test
    fun `mimir button loading state displays indicator and is disabled`() = runComposeUiTest {
        var clicked = false
        setContent {
            MimirTheme {
                MimirButton(
                    text = "Синхронизация",
                    onClick = { clicked = true },
                    loading = true,
                )
            }
        }

        onNodeWithTag("mimir_button_loading").assertIsDisplayed()
        onNodeWithTag("mimir_button").assertIsNotEnabled()
        onNodeWithTag("mimir_button").performClick()
        assertFalse(clicked)
    }

    @Test
    fun `mimir button variants render properly`() = runComposeUiTest {
        setContent {
            MimirTheme {
                MimirButton(text = "Primary", onClick = {}, variant = MimirButtonVariant.Primary)
                MimirButton(text = "Secondary", onClick = {}, variant = MimirButtonVariant.Secondary)
                MimirButton(text = "Ghost", onClick = {}, variant = MimirButtonVariant.Ghost)
                MimirButton(text = "Danger", onClick = {}, variant = MimirButtonVariant.Danger)
            }
        }

        onNodeWithText("Primary").assertIsDisplayed()
        onNodeWithText("Secondary").assertIsDisplayed()
        onNodeWithText("Ghost").assertIsDisplayed()
        onNodeWithText("Danger").assertIsDisplayed()
    }

    @Test
    fun `mimir text field updates value and shows label and placeholder`() = runComposeUiTest {
        var currentText = ""
        setContent {
            MimirTheme {
                var text by remember { mutableStateOf("") }
                MimirTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        currentText = it
                    },
                    label = "Имя папки",
                    placeholder = "Введите имя...",
                    modifier = Modifier.testTag("text_field"),
                )
            }
        }

        onNodeWithText("Имя папки").assertIsDisplayed()
        onNodeWithTag("text_field").performClick()
        onNodeWithText("Введите имя...").assertIsDisplayed()
        onNodeWithTag("text_field").performTextInput("Vault")
        assertEquals("Vault", currentText)
    }

    @Test
    fun `mimir text field password mode toggles visibility`() = runComposeUiTest {
        setContent {
            MimirTheme {
                var password by remember { mutableStateOf("secret123") }
                MimirTextField(
                    value = password,
                    onValueChange = { password = it },
                    isPassword = true,
                    label = "Пароль",
                    modifier = Modifier.testTag("password_field"),
                )
            }
        }

        onNodeWithTag("password_toggle").assertIsDisplayed()
        onNodeWithTag("password_toggle").performClick()
        // Toggle works and doesn't crash
        onNodeWithTag("password_toggle").assertIsDisplayed()
    }

    @Test
    fun `mimir text field error state displays error message`() = runComposeUiTest {
        setContent {
            MimirTheme {
                MimirTextField(
                    value = "wrong",
                    onValueChange = {},
                    error = "Недопустимый путь к хранилищу",
                )
            }
        }

        onNodeWithText("Недопустимый путь к хранилищу").assertIsDisplayed()
    }

    @Test
    fun `mimir card renders column content`() = runComposeUiTest {
        setContent {
            MimirTheme {
                MimirCard(modifier = Modifier.testTag("mimir_card")) {
                    Text("Внутри карточки")
                }
            }
        }

        onNodeWithTag("mimir_card").assertIsDisplayed()
        onNodeWithText("Внутри карточки").assertIsDisplayed()
    }

    @Test
    fun `mimir status badge renders status labels and indicator`() = runComposeUiTest {
        setContent {
            MimirTheme {
                MimirStatusBadge(status = SyncStatus.Idle, modifier = Modifier.testTag("badge_idle"))
                MimirStatusBadge(status = SyncStatus.Syncing, modifier = Modifier.testTag("badge_syncing"))
                MimirStatusBadge(status = SyncStatus.Offline, modifier = Modifier.testTag("badge_offline"))
                MimirStatusBadge(status = SyncStatus.Error("Диск переполнен"), modifier = Modifier.testTag("badge_error"))
                MimirStatusBadge(status = SyncStatus.NotConfigured, modifier = Modifier.testTag("badge_not_configured"))
            }
        }

        onNodeWithText("Mimir: up to date").assertIsDisplayed()
        onNodeWithText("Mimir: syncing").assertIsDisplayed()
        onNodeWithText("Mimir: waiting for network").assertIsDisplayed()
        onNodeWithText("Mimir: error — Диск переполнен").assertIsDisplayed()
        onNodeWithText("Mimir: not connected").assertIsDisplayed()
    }

    @Test
    fun `mimir progress bar renders deterministic and indeterminate states`() = runComposeUiTest {
        setContent {
            MimirTheme {
                MimirProgressBar(progress = 0.42f, modifier = Modifier.testTag("progress_determinate"))
                MimirProgressBar(progress = null, modifier = Modifier.testTag("progress_indeterminate"))
            }
        }

        onNodeWithTag("progress_determinate").assertIsDisplayed()
        onNodeWithTag("progress_indeterminate").assertIsDisplayed()
    }
}
