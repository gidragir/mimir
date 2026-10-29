package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.platform.StorageAccess

@Composable
fun StorageAccessBanner(
    storageAccess: StorageAccess,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = ObsidianStyle.panelBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                SyncTexts.STORAGE_ACCESS_REVOKED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Button(
                onClick = { storageAccess.requestAllFiles() },
                shape = MaterialTheme.shapes.extraSmall,
                colors = ObsidianStyle.buttonColors(),
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
            ) {
                Text(SyncTexts.GRANT)
            }
        }
    }
}

@Composable
fun SignInRequiredBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = ObsidianStyle.panelBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                SyncTexts.SIGN_IN_REQUIRED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Button(
                onClick = onSignIn,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ObsidianStyle.buttonColors(),
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
            ) {
                Text(SyncTexts.SIGN_IN)
            }
        }
    }
}

@Composable
fun StatusBanners(
    model: MimirUiModel,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storageAccess = LocalStorageAccess.current
    val storageGranted by storageAccess?.granted?.collectAsState() ?: androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(true)
    }
    val vaultPath by model.vaultPath.collectAsState()
    val status by model.status.collectAsState()

    val showStorageBanner = storageAccess != null && !storageGranted && vaultPath != null
    val showSignInBanner = status is SyncStatus.Error && (status as SyncStatus.Error).kind == SyncErrorKind.SignInRequired

    if (showStorageBanner || showSignInBanner) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showStorageBanner && storageAccess != null) {
                StorageAccessBanner(storageAccess)
            }
            if (showSignInBanner) {
                SignInRequiredBanner(onSignIn = onOpenSetup)
            }
        }
    }
}
