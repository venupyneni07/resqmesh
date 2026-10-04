package org.resqmesh.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for local payloads, separate from transport authentication/E2EE. */
class LocalCipher(private val keyProvider: () -> SecretKey = { androidKey() }) {
    private val cachedKey: SecretKey by lazy(keyProvider)
    fun encrypt(plain: String, context: String = "payload"): String =
        PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8), context))
    fun decrypt(stored: String, context: String = "payload"): String =
        if (!isEncrypted(stored)) stored else String(decryptBytes(Base64.getDecoder().decode(stored.removePrefix(PREFIX)), context), Charsets.UTF_8)
    fun encryptBytes(bytes: ByteArray, context: String = "payload"): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cachedKey); cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return MAGIC + cipher.iv + cipher.doFinal(bytes)
    }
    fun decryptBytes(bytes: ByteArray, context: String = "payload"): ByteArray {
        if (!isEncrypted(bytes)) return bytes
        require(bytes.size >= MAGIC.size + 12 + 16) { "Encrypted local payload is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cachedKey, GCMParameterSpec(128, bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(MAGIC.size + 12, bytes.size))
    }
    companion object {
        private const val PREFIX = "rqm:enc:v1:"
        private val MAGIC = byteArrayOf(82, 81, 77, 69, 78, 67, 49, 0)
        fun isEncrypted(value: String) = value.startsWith(PREFIX)
        fun isEncrypted(value: ByteArray) = value.size >= MAGIC.size && value.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        @Synchronized private fun androidKey(): SecretKey {
            val alias = "org.resqmesh.payload.v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
