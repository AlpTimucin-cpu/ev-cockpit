package com.evcockpit.data.traffic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure input gate: provider formats vary, but CRLF, controls and long values are forbidden. */
internal object TomTomKeySyntax {
    fun accepted(text: String): Boolean =
        text.length in 8..256 && text.all { it.code in 0x21..0x7e && it != '?' && it != '&' }
}

internal data class TrafficAccess(val enabled: Boolean, val keyAvailable: Boolean) {
    val statusTr: String get() = when {
        !keyAvailable -> "TomTom anahtarı yok · Trafik: kullanılamıyor"
        !enabled -> "TomTom kapalı · Trafik: kullanılamıyor"
        else -> "TomTom etkin · gerçek veri bekleniyor"
    }
}

/**
 * App-private AES-256/GCM credential vault backed by AndroidKeyStore.
 * No plaintext preference/DataStore, network query key, BuildConfig or Android backup.
 * User must explicitly enable outbound location transfer separately from saving a key.
 */
@Singleton
class TomTomCredentialVault @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "evc_tomtom_traffic_private_v1", Context.MODE_PRIVATE
    )
    private val keyAlias = "evc_tomtom_traffic_aes_gcm_v1"
    private val _access = MutableStateFlow(observe())
    val access: StateFlow<TrafficAccess> = _access.asStateFlow()

    @Synchronized
    private fun observe(): TrafficAccess {
        val valid = requestKey() != null
        return TrafficAccess(prefs.getBoolean("location_consent", false) && valid, valid)
    }

    private fun secretKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }

    @Synchronized
    fun requestKey(): String? = runCatching {
        val encrypted = prefs.getString("sealed_key", null) ?: return null
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        if (bytes.size !in 29..1024) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false) ?: return null,
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        val value = String(plain, StandardCharsets.UTF_8)
        plain.fill(0)
        value.takeIf(TomTomKeySyntax::accepted)
    }.getOrNull()

    @Synchronized
    fun saveNewKey(candidate: String): Boolean {
        if (!TomTomKeySyntax.accepted(candidate)) return false
        val encoded = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(secretKey(true)))
            val clear = candidate.toByteArray(StandardCharsets.UTF_8)
            val result = cipher.doFinal(clear)
            clear.fill(0)
            Base64.encodeToString(cipher.iv + result, Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val saved = prefs.edit().putString("sealed_key", encoded)
            .putBoolean("location_consent", false).commit()
        _access.value = observe()
        return saved
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && requestKey() == null) return false
        val saved = prefs.edit().putBoolean("location_consent", enabled).commit()
        _access.value = observe()
        return saved
    }

    @Synchronized
    fun clear(): Boolean {
        val saved = prefs.edit().remove("sealed_key")
            .putBoolean("location_consent", false).commit()
        _access.value = TrafficAccess(false, false)
        return saved
    }
}
