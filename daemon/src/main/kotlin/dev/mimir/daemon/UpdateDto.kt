package dev.mimir.daemon

import dev.mimir.client.UpdateApplyDto
import dev.mimir.core.update.UpdateApplyResult

/** `null` для [UpdateApplyResult.Failed]: ошибка отдаётся отдельным ответом. */
internal fun UpdateApplyResult.toDto(): UpdateApplyDto? = when (this) {
    is UpdateApplyResult.Success -> UpdateApplyDto("applied", message, requiresRestart)
    is UpdateApplyResult.LaunchedInstaller -> UpdateApplyDto("installer_launched", message, requiresRestart = true)
    is UpdateApplyResult.NotificationDispatched -> UpdateApplyDto("notification", "Update details: $url", url = url)
    is UpdateApplyResult.Failed -> null
}
