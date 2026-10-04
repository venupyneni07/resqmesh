package org.resqmesh.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM key material remains in Android Keystore. Never logs or backs up credentials. */
class CredentialStore(context: Context, private val name: String = "resqmesh-credentials") {
    private val encrypted = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "org.resqmesh.$name.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): String {
        val saved = encrypted.getString("gateway", null) ?: return ""
        val packed = Base64.decode(saved, Base64.NO_WRAP)
        require(packed.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }
    @Synchronized fun write(value: String) {
        if (value.isEmpty()) { check(encrypted.edit().remove("gateway").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(encrypted.edit().putString("gateway", Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
    }
    /** Remove the old plaintext only after the encrypted replacement has committed and round-tripped. */
    @Synchronized fun migrate(context: Context): String {
        val legacy = context.getSharedPreferences("resqmesh", Context.MODE_PRIVATE)
        if (legacy.contains("apiKey")) {
            val value = legacy.getString("apiKey", "").orEmpty()
            if (value.isNotEmpty()) { write(value); check(read() == value) }
            check(legacy.edit().remove("apiKey").commit())
        }
        return read()
    }
}
