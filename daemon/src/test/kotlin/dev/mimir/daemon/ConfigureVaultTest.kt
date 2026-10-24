package dev.mimir.daemon

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigureVaultTest {
    private val configDir = Files.createTempDirectory("mimir-config-test")

    @Test
    fun `save and reload daemon config`() {
        DaemonConfig(vaultPath = "/test/vault", configDir = configDir.toString()).save(configDir.toString())
        val loaded = DaemonConfig.load(configDir = configDir.toString(), env = emptyMap())
        assertEquals("/test/vault", loaded.vaultPath)
    }

    @Test
    fun `configureVault rejects non-existent directory`() = runTest {
        val runtime = DaemonRuntime(DaemonConfig(configDir = configDir.toString()))
        try {
            assertFailsWith<IllegalArgumentException> { runtime.configureVault("/non/existent/path/for/sure") }
            assertEquals(null, runtime.vaultPath)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `configureVault persists the path and exposes existing conflicts`() = runTest {
        val vault = Files.createTempDirectory("mimir-vault-test")
        Files.writeString(vault.resolve("A (conflicted copy 2026-10-24-101010).md"), "x")
        val runtime = DaemonRuntime(DaemonConfig(configDir = configDir.toString()))
        try {
            runtime.configureVault(vault.toString())
            assertEquals(vault.toString(), runtime.vaultPath)
            assertEquals(vault.toString(), DaemonConfig.load(configDir.toString(), emptyMap()).vaultPath)
            assertEquals(listOf("A.md"), runtime.conflictsFlow.value.map { it.originalPath })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `saving the vault does not persist environment secrets`() = runTest {
        val vault = Files.createTempDirectory("mimir-vault-test")
        val runtime = DaemonRuntime(DaemonConfig(configDir = configDir.toString(), password = "from-env", token = "tok"))
        try {
            runtime.configureVault(vault.toString())
            val text = Files.readString(configDir.resolve("config.json"))
            assertEquals(false, "from-env" in text || "tok" in text, text)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `POST vault configures the vault and maps bad paths to 400`() = testApplication {
        val service = FakeDaemonService()
        application { mimirRoutes(service, DaemonConfig()) }
        val ok = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/ok"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("/ok", service.vaultPath)
        service.rejectVault = true
        val bad = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/bad"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
