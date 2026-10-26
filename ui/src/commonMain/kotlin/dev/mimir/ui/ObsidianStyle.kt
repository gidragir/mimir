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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Мелкие помощники оформления в духе Obsidian: тонкие границы, компактные кнопки, тёмные поля ввода. */
object ObsidianStyle {
    private val InputDark = Color(0xFF141414)
    private val InputLight = Color(0xFFF0F0F0)

    val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val minTouchTarget = 48.dp
    val compactButtonHeight = 32.dp

    fun Modifier.minTouchTarget(): Modifier = heightIn(min = minTouchTarget)
    fun Modifier.compactButton(): Modifier = heightIn(min = compactButtonHeight)

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
        val container = if (isSystemInDarkTheme()) InputDark else InputLight
        return OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        )
    }
}
