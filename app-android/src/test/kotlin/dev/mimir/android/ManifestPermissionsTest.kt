package dev.mimir.android

import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
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
    fun `worker requests are built with connected network constraints`() {
        val periodic = MimirSyncWorker.buildPeriodicRequest(repeatIntervalMinutes = 15, requiresCharging = true)
        assertEquals(NetworkType.CONNECTED, periodic.workSpec.constraints.requiredNetworkType)
        assertTrue(periodic.workSpec.constraints.requiresCharging())

        val expedited = MimirSyncWorker.buildExpeditedRequest()
        assertEquals(NetworkType.CONNECTED, expedited.workSpec.constraints.requiredNetworkType)
        assertTrue(expedited.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, expedited.workSpec.outOfQuotaPolicy)
    }
}
