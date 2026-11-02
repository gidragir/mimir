package dev.mimir.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

/** Мелкие помощники оформления в духе Obsidian: тонкие границы, компактные кнопки, тёмные поля ввода. */
object ObsidianStyle {
    val buttonPadding = PaddingValues(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space4)
    val minTouchTarget = 48.dp
    val compactButtonHeight = 32.dp

    /** Высота интерактивного элемента: 48 dp на сенсорных экранах, компактные 32 dp на десктопе. */
    fun Modifier.compactButton(): Modifier = composed {
        heightIn(min = if (LocalTouchInput.current) minTouchTarget else compactButtonHeight)
    }

    @Composable
    fun panelBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

    @Composable
    fun panelColors(): CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

    @Composable
    fun buttonColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )

    @Composable
    fun fieldColors(): TextFieldColors {
        val container = if (isSystemInDarkTheme()) MimirTokens.DarkInput else MimirTokens.LightInput
        return OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            errorContainerColor = container,
            errorBorderColor = MimirTokens.StatusError,
            errorSupportingTextColor = MimirTokens.StatusError,
        )
    }
}
