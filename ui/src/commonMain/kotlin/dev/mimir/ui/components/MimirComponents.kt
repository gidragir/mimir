package dev.mimir.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton

enum class MimirButtonVariant {
    Primary,
    Secondary,
    Ghost,
    Danger,
}

@Composable
fun MimirButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MimirButtonVariant = MimirButtonVariant.Primary,
    loading: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val isInteractive = enabled && !loading

    val colors = when (variant) {
        MimirButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = MimirTokens.Accent,
            contentColor = Color.White,
            disabledContainerColor = MimirTokens.Accent.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        )
        MimirButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        MimirButtonVariant.Ghost -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        MimirButtonVariant.Danger -> ButtonDefaults.buttonColors(
            containerColor = MimirTokens.StatusError,
            contentColor = Color.White,
            disabledContainerColor = MimirTokens.StatusError.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        )
    }

    val border = when (variant) {
        MimirButtonVariant.Secondary -> BorderStroke(
            1.dp,
            if (isInteractive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
        else -> null
    }

    Button(
        onClick = onClick,
        modifier = Modifier
            .testTag("mimir_button")
            .compactButton()
            .then(modifier),
        enabled = isInteractive,
        shape = RoundedCornerShape(MimirTokens.Radius6),
        colors = colors,
        border = border,
        contentPadding = ObsidianStyle.buttonPadding,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag("mimir_button_loading"),
                    strokeWidth = 2.dp,
                    color = when (variant) {
                        MimirButtonVariant.Ghost -> MaterialTheme.colorScheme.primary
                        MimirButtonVariant.Secondary -> MaterialTheme.colorScheme.onSurface
                        else -> Color.White
                    },
                )
            } else if (icon != null) {
                icon()
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun MimirTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    isPassword: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    val visualTransformation = if (isPassword && !passwordVisible) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let {
            { Text(it, color = MimirTokens.StatusError) }
        },
        visualTransformation = visualTransformation,
        trailingIcon = if (isPassword) {
            {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier
                        .testTag("password_toggle")
                        .semantics {
                            contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                        },
                ) {
                    Icon(
                        imageVector = if (passwordVisible) MimirIcons.EyeOff else MimirIcons.Eye,
                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else null,
        shape = RoundedCornerShape(MimirTokens.Radius6),
        colors = ObsidianStyle.fieldColors(),
    )
}

@Composable
fun MimirCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(MimirTokens.Radius8),
        colors = ObsidianStyle.panelColors(),
        border = ObsidianStyle.panelBorder(),
        content = content,
    )
}

@Composable
fun MimirStatusBadge(
    status: SyncStatus,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (status) {
        SyncStatus.Idle -> MimirTokens.StatusSuccess
        SyncStatus.Syncing -> MimirTokens.Accent
        SyncStatus.Offline -> MimirTokens.StatusOffline
        is SyncStatus.Error -> MimirTokens.StatusError
        SyncStatus.NotConfigured -> MimirTokens.StatusOffline
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MimirTokens.Radius6))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(MimirTokens.Radius6),
            )
            .padding(horizontal = MimirTokens.Space8, vertical = MimirTokens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.25f)),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(
            text = status.label(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun MimirProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val barShape = RoundedCornerShape(MimirTokens.RadiusXs)
    val barModifier = modifier
        .height(4.dp)
        .clip(barShape)

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = barModifier,
            color = MimirTokens.Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    } else {
        LinearProgressIndicator(
            modifier = barModifier,
            color = MimirTokens.Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
