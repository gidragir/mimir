// client/src/commonTest/kotlin/dev/mimir/client/DaemonDtoSerializationTest.kt
package dev.mimir.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testStatusDtoSerializationRoundTrip() {
        val dto = DaemonStatusDto(
            status = "syncing",
            label = "Mimir: syncing",
            vaultPath = "/path/to/vault",
            lastSyncAt = "2026-10-26T12:00:00Z",
            isLocked = false,
            activeConflicts = 2,
            accountEmail = "user@gmail.com",
            progress = null,
        )
        val serialized = json.encodeToString(DaemonStatusDto.serializer(), dto)
        val deserialized = json.decodeFromString(DaemonStatusDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun testDeviceCodeDtoSerialization() {
        val dto = DaemonDeviceCodeDto(
            deviceCode = "dev_123",
            userCode = "ABCD-1234",
            verificationUrl = "https://google.com/device",
            expiresInSeconds = 1800,
            intervalSeconds = 5,
        )
        val serialized = json.encodeToString(DaemonDeviceCodeDto.serializer(), dto)
        val deserialized = json.decodeFromString(DaemonDeviceCodeDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }
}
