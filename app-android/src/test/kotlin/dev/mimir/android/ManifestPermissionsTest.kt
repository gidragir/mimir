package dev.mimir.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


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

    @Test
    fun `main activity handles mimir authorized deep link`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        var foundDeepLink = false
        var singleTop = false
        for (i in 0 until activities.length) {
            val act = activities.item(i) as org.w3c.dom.Element
            if (act.getAttributeNS(androidName, "name") == ".MainActivity") {
                if (act.getAttributeNS(androidName, "launchMode") == "singleTop") {
                    singleTop = true
                }
                val filters = act.getElementsByTagName("intent-filter")
                for (j in 0 until filters.length) {
                    val filter = filters.item(j) as org.w3c.dom.Element
                    val dataNodes = filter.getElementsByTagName("data")
                    for (k in 0 until dataNodes.length) {
                        val data = dataNodes.item(k) as org.w3c.dom.Element
                        if (data.getAttributeNS(androidName, "scheme") == "mimir" &&
                            data.getAttributeNS(androidName, "host") == "authorized"
                        ) {
                            foundDeepLink = true
                        }
                    }
                }
            }
        }
        assertTrue(singleTop, "MainActivity must have launchMode=singleTop")
        assertTrue(foundDeepLink, "MainActivity must declare mimir://authorized deep link")
    }
}

