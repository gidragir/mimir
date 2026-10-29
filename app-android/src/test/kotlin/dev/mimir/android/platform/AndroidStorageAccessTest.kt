package dev.mimir.android.platform

import android.app.Activity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidStorageAccessTest {

    @Test
    fun `granted reflects isExternalManager and refreshes`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var managerState = false
        val storageAccess = AndroidStorageAccess(activity, isExternalManager = { managerState })

        assertFalse(storageAccess.granted.value)

        managerState = true
        storageAccess.refresh()
        assertTrue(storageAccess.granted.value)
    }

    @Test
    fun `requestAllFiles launches settings intent`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val storageAccess = AndroidStorageAccess(activity, isExternalManager = { false })

        storageAccess.requestAllFiles()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertTrue(nextIntent != null)
        assertTrue(nextIntent.action?.contains("MANAGE") == true)
        assertEquals("package:${activity.packageName}", nextIntent.dataString)
    }

    @Test
    fun `requestNotifications invokes callback`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var requested = false
        val storageAccess = AndroidStorageAccess(
            activity,
            isExternalManager = { true },
            notificationRequester = { requested = true },
        )

        storageAccess.requestNotifications()
        assertTrue(requested)
    }
}
