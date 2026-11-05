package dev.mimir.desktop

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformFocusTest {
    @Test
    fun testActivateRestoresAndBringsFrameToFront() {
        if (GraphicsEnvironment.isHeadless()) return
        val frame = Frame("Test Frame")
        frame.extendedState = Frame.ICONIFIED
        frame.isVisible = false

        PlatformFocus.activate(frame)

        assertTrue(frame.isVisible)
        assertEquals(Frame.NORMAL, frame.extendedState)
        frame.dispose()
    }
}
