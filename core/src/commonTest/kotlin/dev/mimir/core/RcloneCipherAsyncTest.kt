package dev.mimir.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class RcloneCipherAsyncTest {
    @Test
    fun testAsyncCipherDerivationProducesValidCipher() = runTest {
        val cipher = RcloneCipher.create("test-master-password")
        assertNotNull(cipher)
        val encryptedName = cipher.encryptName("note.md")
        val decryptedName = cipher.decryptName(encryptedName)
        assertEquals("note.md", decryptedName)
    }
}
