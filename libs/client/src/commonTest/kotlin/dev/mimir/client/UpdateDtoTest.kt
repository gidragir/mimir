package dev.mimir.client

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import dev.mimir.core.update.UpdateStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class UpdateDtoTest {
    private val asset = UpdateAsset("m.msi", "https://x/m.msi", 10, null, TargetPlatform.WINDOWS_X64, AssetKind.MSI)
    private val release = UpdateRelease(SemVer.parse("0.2.0"), "notes", Instant.fromEpochSeconds(0), "https://x/r", listOf(asset))
    private val available = UpdateState.Available(release, asset, ApplierCapability.DOWNLOAD_AND_LAUNCH)

    @Test
    fun `error status carries step and a stable code`() {
        val dto = UpdateState.Error("Checksum mismatch", available, UpdateStep.DOWNLOAD).toDto("0.1.0")
        assertEquals(UpdateStatus.ERROR, dto.status)
        assertEquals("DOWNLOAD", dto.failedStep)
        assertEquals("CHECKSUM_MISMATCH", dto.errorCode)
        assertEquals("0.2.0", dto.latestVersion)
        assertEquals("RATE_LIMITED", UpdateState.Error("GitHub rate limit", UpdateState.Idle()).toDto("0.1.0").errorCode)
        assertEquals("CHECK", UpdateState.Error("x", UpdateState.Idle()).toDto("0.1.0").failedStep)
        assertEquals("INSTALL", UpdateState.Error("x", available, UpdateStep.INSTALL).toDto("0.1.0").failedStep)
    }

    @Test
    fun `blockers use shared rules and treat an error as its previous state`() {
        assertNull(available.downloadBlocker())
        assertNull(UpdateState.Error("x", available, UpdateStep.DOWNLOAD).downloadBlocker())
        assertEquals(UpdateErrorCode.NO_INSTALLABLE_FILE, UpdateState.Available(release, null, ApplierCapability.NOTIFY_ONLY).downloadBlocker())
        assertEquals(UpdateErrorCode.NOTHING_TO_DOWNLOAD, UpdateState.Idle().downloadBlocker())
        assertEquals(UpdateErrorCode.NOT_DOWNLOADED, available.applyBlocker(false))
        assertEquals(UpdateErrorCode.INSTALL_IN_PROGRESS, UpdateState.Installing(release).applyBlocker(false))
    }

    @Test
    fun `apply results map to dto and failure to null`() {
        assertEquals("applied", UpdateApplyResult.Success("ok", false).toDto()?.result)
        assertEquals(true, UpdateApplyResult.LaunchedInstaller("ok").toDto()?.requiresRestart)
        assertEquals("https://x/r", UpdateApplyResult.NotificationDispatched("https://x/r").toDto()?.url)
        assertNull(UpdateApplyResult.Failed("boom").toDto())
    }
}
