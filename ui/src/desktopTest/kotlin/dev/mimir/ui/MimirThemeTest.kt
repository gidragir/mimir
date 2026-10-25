package dev.mimir.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MimirThemeTest {
    @Test
    fun `obsidian color tokens match spec`() {
        assertEquals(Color(0xFF1E1E1E), ObsidianDarkColors.background)
        assertEquals(Color(0xFF705DCF), ObsidianDarkColors.primary)
        assertEquals(Color(0xFFFFFFFF), ObsidianLightColors.background)
        assertEquals(Color(0xFF705DCF), ObsidianLightColors.primary)
    }

    @Test
    fun `mimir theme provides compact obsidian shapes`() = runComposeUiTest {
        var extraSmall: Any? = null
        var small: Any? = null
        setContent {
            MimirTheme {
                extraSmall = MaterialTheme.shapes.extraSmall
                small = MaterialTheme.shapes.small
            }
        }
        assertEquals(RoundedCornerShape(4.dp), extraSmall)
        assertEquals(RoundedCornerShape(6.dp), small)
    }
}
