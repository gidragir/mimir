package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretBoxTest {
    // libsodium test/default/secretbox.c (+ .exp): сообщение m[32..163), результат c[16..163)
    private val key = "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389".hexToBytes()
    private val nonce = "69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37".hexToBytes()
    private val message = (
        "be075fc53c81f2d5cf141316ebeb0c7b5228c52a4c62cbd44b66849b64244ffce5ecbaaf33bd751a1ac728d45e6c6129" +
            "6cdc3c01233561f41db66cce314adb310e3be8250c46f06dceea3a7fa1348057e2f6556ad6b1318a024a838f21af1fde" +
            "048977eb48f59ffd4924ca1c60902e52f0a089bc76897040e082f937763848645e0705"
        ).hexToBytes()
    private val box = (
        "f3ffc7703f9400e52a7dfb4b3d3305d98e993b9f48681273c29650ba32fc76ce48332ea7164d96a4476fb8c531a1186a" +
            "c0dfc17c98dce87b4da7f011ec48c97271d2c20f9b928fe2270d6fb863d51738b48eeee314a7cc8ab932164548e526ae" +
            "90224368517acfeabd6bb3732bc0e9da99832b61ca01b6de56244a9e88d5f9b37973f622a43d14a6599b1f654cb45a74" +
            "e355a5"
        ).hexToBytes()

    @Test
    fun `poly1305 rfc8439 vector`() {
        val polyKey = "85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b".hexToBytes()
        val data = "Cryptographic Forum Research Group".encodeToByteArray()
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", Poly1305.mac(polyKey, data).toHex())
    }

    @Test
    fun `secretbox nacl vector`() {
        assertContentEquals(box, SecretBox.seal(key, nonce, message))
        assertContentEquals(message, SecretBox.open(key, nonce, box))
    }

    @Test
    fun `open rejects flipped tag byte`() {
        val bad = box.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFailsWith<CryptException> { SecretBox.open(key, nonce, bad) }
    }

    @Test
    fun `open rejects flipped body byte`() {
        val bad = box.copyOf().also { it[40] = (it[40].toInt() xor 0x80).toByte() }
        assertFailsWith<CryptException> { SecretBox.open(key, nonce, bad) }
    }

    @Test
    fun `open rejects short input`() {
        assertFailsWith<CryptException> { SecretBox.open(key, nonce, ByteArray(15)) }
    }

    @Test
    fun `seal then open roundtrip for lengths 0,1,63,64,65,65536`() {
        for (len in intArrayOf(0, 1, 63, 64, 65, 65536)) {
            val plain = ByteArray(len) { (it * 31 + 7).toByte() }
            val sealed = SecretBox.seal(key, nonce, plain)
            assertEquals(len + 16, sealed.size)
            assertContentEquals(plain, SecretBox.open(key, nonce, sealed))
        }
    }
}
