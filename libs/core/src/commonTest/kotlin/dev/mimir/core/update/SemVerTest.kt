package dev.mimir.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun parseStandardVersions() {
        val v1 = SemVer.parse("0.1.0")
        assertEquals(0, v1.major)
        assertEquals(1, v1.minor)
        assertEquals(0, v1.patch)
        assertEquals(null, v1.preRelease)

        val v2 = SemVer.parse("v1.23.456-beta.2")
        assertEquals(1, v2.major)
        assertEquals(23, v2.minor)
        assertEquals(456, v2.patch)
        assertEquals("beta.2", v2.preRelease)
    }

    @Test
    fun comparisonRules() {
        assertTrue(SemVer.parse("0.2.0").isNewerThan(SemVer.parse("0.1.9")))
        assertTrue(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("0.9.9")))
        assertFalse(SemVer.parse("0.1.0").isNewerThan(SemVer.parse("0.1.0")))
        assertFalse(SemVer.parse("0.1.0").isNewerThan(SemVer.parse("0.1.1")))
        assertTrue(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("1.0.0-rc.1")))
        assertFalse(SemVer.parse("1.0.0-rc.1").isNewerThan(SemVer.parse("1.0.0")))
    }

    @Test
    fun invalidVersionsThrow() {
        assertFailsWith<IllegalArgumentException> { SemVer.parse("not-a-version") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.0") }
    }

    @Test
    fun toStringRendersWithoutLeadingV() {
        assertEquals("1.2.3", SemVer.parse("v1.2.3").toString())
        assertEquals("1.2.3-rc.1", SemVer.parse("v1.2.3-rc.1").toString())
        assertEquals("0.1.0", SemVer(0, 1, 0).toString())
    }

    @Test
    fun preReleaseOrderingFollowsSemver() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
            "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map { SemVer.parse(it) }
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i + 1].isNewerThan(ordered[i]), "${ordered[i + 1]} > ${ordered[i]}")
            assertTrue(ordered[i] < ordered[i + 1])
        }
        assertEquals(0, SemVer.parse("1.0.0-beta.2").compareTo(SemVer.parse("1.0.0-beta.2")))
    }
}
