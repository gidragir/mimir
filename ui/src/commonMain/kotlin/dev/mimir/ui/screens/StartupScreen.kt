package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.mimir.ui.MimirTheme

/** Окно, пока приложение поднимает демон, или сообщение, почему не поднялось. */
@Composable
fun StartupScreen(message: String) {
    MimirTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
        }
    }
}
