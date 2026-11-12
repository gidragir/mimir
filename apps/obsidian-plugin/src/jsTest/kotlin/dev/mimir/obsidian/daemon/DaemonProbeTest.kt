package dev.mimir.obsidian.daemon

import dev.mimir.obsidian.fakes.FakeRequestUrl
import dev.mimir.obsidian.platform.UrlResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DaemonProbeTest {

    private val port = 47821

    @Test
    fun notDesktopIsAbsentWithoutRequest() = runTest {
        val fake = FakeRequestUrl()
        val probe = DaemonProbe(fake, port = port, isDesktop = false, vaultBasePath = "/home/user/Vault")

        val result = probe.check()
        assertEquals(DaemonPresence.Absent, result)
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun noResponseIsAbsent() = runTest {
        val fake = FakeRequestUrl {
            throw IOException("Connection refused")
        }
        val probe = DaemonProbe(fake, port = port, isDesktop = true, vaultBasePath = "/home/user/Vault")

        val result = probe.check()
        assertEquals(DaemonPresence.Absent, result)
        assertEquals("http://127.0.0.1:$port/api/v1/status", fake.lastRequest.url)
    }

    @Test
    fun timeoutIsAbsent() = runTest {
        val fake = FakeRequestUrl {
            delay(2.seconds)
            UrlResponse(200, emptyMap(), """{"vaultPath":"/home/user/Vault"}""".encodeToByteArray())
        }
        val probe = DaemonProbe(
            request = fake,
            port = port,
            isDesktop = true,
            vaultBasePath = "/home/user/Vault",
            timeout = 100.milliseconds,
        )

        val result = probe.check()
        assertEquals(DaemonPresence.Absent, result)
    }

    @Test
    fun matchingVaultPath() = runTest {
        // Linux trailing slash normalization
        var fake = FakeRequestUrl {
            UrlResponse(200, emptyMap(), """{"vaultPath":"/home/user/Vault/"}""".encodeToByteArray())
        }
        var probe = DaemonProbe(fake, port = port, isDesktop = true, vaultBasePath = "/home/user/Vault")
        assertEquals(DaemonPresence.ServesThisVault, probe.check())

        // Windows drive letter and backslash normalization
        fake = FakeRequestUrl {
            UrlResponse(200, emptyMap(), """{"vaultPath":"C:\\Users\\User\\Vault\\"}""".encodeToByteArray())
        }
        probe = DaemonProbe(fake, port = port, isDesktop = true, vaultBasePath = "c:/Users/User/Vault")
        assertEquals(DaemonPresence.ServesThisVault, probe.check())
    }

    @Test
    fun otherVaultPath() = runTest {
        val fake = FakeRequestUrl {
            UrlResponse(200, emptyMap(), """{"vaultPath":"/home/user/OtherVault"}""".encodeToByteArray())
        }
        val probe = DaemonProbe(fake, port = port, isDesktop = true, vaultBasePath = "/home/user/Vault")

        assertEquals(DaemonPresence.ServesOtherVault, probe.check())
    }

    @Test
    fun unauthorizedDaemonYields() = runTest {
        val fake = FakeRequestUrl {
            UrlResponse(401, emptyMap(), """{"error":"Unauthorized"}""".encodeToByteArray())
        }
        val probe = DaemonProbe(fake, port = port, isDesktop = true, vaultBasePath = "/home/user/Vault")

        assertEquals(DaemonPresence.Unauthorized, probe.check())
    }
}
