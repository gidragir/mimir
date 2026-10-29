package dev.mimir.build

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionCodeTest {
    @Test
    fun `versionCode calculation for standard semantic version`() {
        assertEquals(10412, VersionCode.versionCodeOf("1.4.12"))
    }

    @Test
    fun `versionCode calculation for minor-only version`() {
        assertEquals(100, VersionCode.versionCodeOf("0.1.0"))
    }

    @Test
    fun `versionCode calculation for version with pre-release suffix`() {
        assertEquals(10203, VersionCode.versionCodeOf("1.2.3-beta.1"))
    }
}
