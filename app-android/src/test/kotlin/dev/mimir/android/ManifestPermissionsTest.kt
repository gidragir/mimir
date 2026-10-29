package dev.mimir.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManifestPermissionsTest {
    private val androidName = "http://schemas.android.com/apk/res/android"

    @Test
    fun `manifest declares all required permissions and omits foreground data sync service`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val declared = document.getElementsByTagName("uses-permission").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(androidName, "name") }
        }.toSet()

        val required = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
        )
        assertEquals(required, declared)
        assertFalse(declared.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun `application name is MimirApplication`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val app = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals(".MimirApplication", app.getAttributeNS(androidName, "name"))
    }

    @Test
    fun `no foreground service declared`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        assertEquals(0, services.length)
    }
}
