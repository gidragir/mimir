package dev.mimir.drive.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testDeserializeFileList() {
        val payload = """
            {
                "kind": "drive#fileList",
                "nextPageToken": "token123",
                "files": [
                    {
                        "id": "file1",
                        "name": "doc.txt",
                        "mimeType": "text/plain",
                        "size": "1024",
                        "modifiedTime": "2026-10-23T12:00:00.000Z",
                        "md5Checksum": "abc123hash",
                        "parents": ["parent1"],
                        "trashed": false
                    },
                    {
                        "id": "dir1",
                        "name": "folder",
                        "mimeType": "application/vnd.google-apps.folder",
                        "trashed": false
                    }
                ]
            }
        """.trimIndent()

        val list = json.decodeFromString<GoogleDriveFileList>(payload)
        assertEquals("token123", list.nextPageToken)
        assertEquals(2, list.files.size)
        assertEquals(false, list.files[0].isDirectory)
        assertEquals(1024L, list.files[0].size)
        assertTrue(list.files[1].isDirectory)
    }

    @Test
    fun testDeserializeTokenResponse() {
        val payload = """
            {
                "access_token": "ya29.test",
                "refresh_token": "1//test_refresh",
                "expires_in": 3600,
                "token_type": "Bearer"
            }
        """.trimIndent()
        val tokens = json.decodeFromString<GoogleTokenResponse>(payload)
        assertEquals("ya29.test", tokens.access_token)
        assertEquals("1//test_refresh", tokens.refresh_token)
        assertEquals(3600L, tokens.expires_in)
    }
}
