package com.peerdone.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeshCryptoTest {

    @Test
    fun encryptWithKey_decryptWithKey_roundtrip() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "Secret message for mesh"
        val encrypted = MeshCrypto.encryptWithKey(plain, key)
        assertNotEquals(plain, encrypted.cipherTextBase64)
        val decrypted = MeshCrypto.decryptWithKey(
            encrypted.ivBase64,
            encrypted.cipherTextBase64,
            key,
        )
        assertEquals(plain, decrypted)
    }

    @Test
    fun encryptControl_decryptControl_roundtrip() {
        val plain = "ack"
        val enc = MeshCrypto.encryptControl(plain)
        val dec = MeshCrypto.decryptControl(enc.ivBase64, enc.cipherTextBase64)
        assertEquals(plain, dec)
    }

    @Test
    fun encryptWithKey_different_iv_each_time() {
        val key = ByteArray(32) { 1 }
        val e1 = MeshCrypto.encryptWithKey("same", key)
        val e2 = MeshCrypto.encryptWithKey("same", key)
        assertNotEquals(e1.ivBase64, e2.ivBase64)
        assertNotEquals(e1.cipherTextBase64, e2.cipherTextBase64)
    }

    @Test
    fun CONTROL_KEY_ID_constant() {
        assertEquals("control:v1", MeshCrypto.CONTROL_KEY_ID)
    }

    @Test
    fun decryptWithKey_wrong_key_fails() {
        val key = ByteArray(32) { it.toByte() }
        val enc = MeshCrypto.encryptWithKey("secret", key)
        val wrongKey = ByteArray(32) { 0 }
        try {
            MeshCrypto.decryptWithKey(enc.ivBase64, enc.cipherTextBase64, wrongKey)
            throw AssertionError("Expected exception")
        } catch (_: Exception) {
            // expected
        }
    }
}
