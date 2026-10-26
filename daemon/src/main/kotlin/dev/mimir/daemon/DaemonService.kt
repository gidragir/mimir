package dev.mimir.daemon

import dev.mimir.client.DaemonBrowserLoginPoll
import dev.mimir.client.DaemonDevicePoll
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonService as ClientDaemonService
import dev.mimir.client.DaemonVaultInspectionDto

typealias DaemonService = ClientDaemonService
typealias VaultInspectionDto = DaemonVaultInspectionDto
typealias LoginMethod = DaemonLoginMethod
typealias DevicePoll = DaemonDevicePoll
typealias BrowserLoginPoll = DaemonBrowserLoginPoll
