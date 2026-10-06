package com.codeassist.ai.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the Gemini API key) with an AES-256-GCM key that lives in the
 * Android Keystore. The key never leaves secure hardware / the keystore process; only the
 * ciphertext is written to SharedPreferences.
 */
object SecureStore {
    private const val ALIAS = "codeassist_secret_v1"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER)
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Throws if the keystore is unavailable; callers show "could not save key". */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    /** Returns null if the blob is damaged or the keystore key was lost (e.g. app data restored). */
    fun decrypt(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
