package dev.mimir.drive.upload

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveUploaderUpdateTest {
    /**
     * Drive v3 обновляет содержимое существующего файла только методом PATCH на `/upload/drive/v3/files/{id}`;
     * POST на этот адрес отвечает 404 Not Found, хотя файл существует.
     */
    @Test
    fun smallUpdateOfExistingFileUsesPatchBecauseDriveAnswers404ToPost() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val folder = "application/vnd.google-apps.folder"
            when {
                request.url.parameters["uploadType"] == "multipart" -> {
                    methods += request.method
                    if (request.method == HttpMethod.Patch) {
                        respond("""{"id":"existing_id","name":"Wow.md","mimeType":"text/markdown","md5Checksum":"m"}""", HttpStatusCode.OK, json)
                    } else {
                        respond("""{"error":{"code":404,"message":"Not Found"}}""", HttpStatusCode.NotFound, json)
                    }
                }
                request.url.parameters["q"]?.contains("'root' in parents") == true ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"$folder"}]}""", HttpStatusCode.OK, json)
                request.url.parameters["q"]?.contains("Wow.md") == true ->
                    respond("""{"files":[{"id":"existing_id","name":"Wow.md","mimeType":"text/markdown","parents":["mimir_root"]}]}""", HttpStatusCode.OK, json)
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, json)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val driveClient = DriveHttpClient(http, InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L)), GoogleAuthClients(null, auth))
        val uploader = DriveUploader(driveClient, DrivePathResolver(driveClient, "Mimir"))

        val entry = uploader.upload(RemotePath("Wow.md"), Buffer().also { it.write("changed".encodeToByteArray()) })

        assertEquals(listOf(HttpMethod.Patch), methods)
        assertEquals("m", entry.revision)
    }
}
