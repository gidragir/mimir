package dev.mimir.drive

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.api.GoogleDriveFileList
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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ревизия из ответа на отправку должна совпадать с ревизией из листинга: иначе план следующего
 * прогона увидит «изменённый удалённый файл» и скачает только что отправленную заметку.
 */
class GoogleDriveStorageRevisionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val root = GoogleDriveFile("mimir_root", "Mimir", DrivePathResolver.FOLDER_MIME_TYPE, parents = listOf("my_drive"))
    private val uploaded = GoogleDriveFile(
        id = "note_id",
        name = "note.md",
        mimeType = "application/octet-stream",
        size = 5,
        modifiedTime = "2026-10-24T12:00:00.000Z",
        md5Checksum = "md5-note",
        parents = listOf("mimir_root"),
    )

    @Test
    fun uploadRevisionMatchesListingRevision() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        var stored = false
        val engine = MockEngine { request ->
            requests += request
            val q = request.url.parameters["q"]
            when {
                q?.contains("'root' in parents") == true ->
                    respond(list(root), HttpStatusCode.OK, jsonHeaders)
                q == "trashed = false" ->
                    respond(if (stored) list(root, uploaded) else list(root), HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["uploadType"] == "multipart" -> {
                    stored = true
                    respond(asDriveWould(uploaded, request.url.parameters["fields"]), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond(list(), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val client = DriveHttpClient(http, InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L)), GoogleAuthClients(null, auth))
        val storage = GoogleDriveStorage(client, DrivePathResolver(client, "Mimir"))

        val written = storage.write(RemotePath("note.md"), Buffer().also { it.write("hello".encodeToByteArray()) })
        val listed = storage.listAll().single { it.path == RemotePath("note.md") }

        assertNotNull(written.revision)
        assertEquals(listed.revision, written.revision)
        assertEquals(listed.size, written.size)
        assertEquals(listed.modifiedAt, written.modifiedAt)
        val uploadFields = requests.single { it.url.parameters["uploadType"] == "multipart" }.url.parameters["fields"].orEmpty()
        assertTrue("md5Checksum" in uploadFields.split(','), "upload fields: '$uploadFields'")
    }

    private fun list(vararg files: GoogleDriveFile) =
        json.encodeToString(GoogleDriveFileList.serializer(), GoogleDriveFileList(files.toList()))

    /** Drive v3 без `fields` отвечает на create/update только kind, id, name и mimeType. */
    private fun asDriveWould(file: GoogleDriveFile, fields: String?): String {
        val requested = fields?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        val parts = mutableListOf(
            "\"kind\":\"drive#file\"",
            "\"id\":\"${file.id}\"",
            "\"name\":\"${file.name}\"",
            "\"mimeType\":\"${file.mimeType}\"",
        )
        if ("size" in requested) parts += "\"size\":\"${file.size}\""
        if ("modifiedTime" in requested) parts += "\"modifiedTime\":\"${file.modifiedTime}\""
        if ("md5Checksum" in requested) parts += "\"md5Checksum\":\"${file.md5Checksum}\""
        return "{" + parts.joinToString(",") + "}"
    }
}
