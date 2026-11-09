package com.evcockpit.mediahub

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Store IPTV stream URIs (often embedding provider credentials) encrypted at
 * rest. Encryption keys never leave the Android Keystore and are not backed up.
 *
 * On-device legacy plaintext URIs remain readable until the catalogue is
 * next written. An encrypted value with a missing key is NOT treated as a URL.
 */
object PrivateUrlCodec {
    private const val KEY_ALIAS = "evcockpit_media_private_uri_v1"
    private const val PREFIX = "evcockpitenc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile private var keyCache: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        keyCache?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            keyCache = it
            return it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey().also { keyCache = it }
    }

    fun encode(value: String): String {
        if (value.isEmpty() || value.startsWith(PREFIX)) return value
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decode(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        require(bytes.size > 28) { "Corrupt private stream URL" }
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
