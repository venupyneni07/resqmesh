package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import org.resqmesh.app.data.LocalCipher
import javax.crypto.KeyGenerator

class LocalCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = LocalCipher { key }
    @Test fun uniqueCiphertextsRoundTripAndLegacyDataReadable() {
        val first = cipher.encrypt("Sensitive SOS", "report:1")
        assertFalse(first.contains("Sensitive SOS"))
        assertNotEquals(first, cipher.encrypt("Sensitive SOS", "report:1"))
        assertEquals("Sensitive SOS", cipher.decrypt(first, "report:1"))
        assertEquals("old plain report", cipher.decrypt("old plain report"))
    }
    @Test fun tamperAndWrongRecordContextAreRejected() {
        val encrypted = cipher.encryptBytes("attachment".toByteArray(), "attachment:1")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decryptBytes(encrypted, "attachment:1") }.isFailure)
        val text = cipher.encrypt("SOS", "report:1")
        assertTrue(runCatching { cipher.decrypt(text, "report:2") }.isFailure)
    }
}
