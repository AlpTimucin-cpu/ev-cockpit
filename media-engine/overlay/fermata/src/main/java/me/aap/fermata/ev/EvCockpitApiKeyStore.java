package me.aap.fermata.ev;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Own-device TomTom credential storage. Based on the submitted
 * EvCockpitApiKeyStore.java, hardened for this public APK:
 *
 * - Android Keystore-backed AES-256-GCM on API 23+ ONLY;
 * - never fall back to unencrypted/plain/Base64 preferences;
 * - app-private preferences hold only IV + authenticated ciphertext;
 * - no secret values or cryptographic exception messages go to logs;
 * - no JS-exposed getter/setter bridge is registered.
 *
 * The existing native Settings dialog owns set/clear. The Leaflet-based
 * traffic provider still needs the key in network requests; encryption at
 * rest does not protect the key from a compromised runtime or JS context.
 */
public final class EvCockpitApiKeyStore {
    private static final String PREFS = "evcockpit_private_settings";
    private static final String PREF_KEY = "tomtom_api_key";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "evcockpit_tomtom_key_aes";
    private static final String VERSION = "v1:";
    private EvCockpitApiKeyStore() {}

    public static boolean valid(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{15,200}");
    }

    /** Returns the key only to privileged in-app Java code, or null. */
    public static synchronized String get(Context context) {
        if (Build.VERSION.SDK_INT < 23) return null;
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString(PREF_KEY, null);
        if (stored == null) return null;
        try {
            if (!stored.startsWith(VERSION)) throw new IllegalStateException("format");
            String[] parts = stored.substring(VERSION.length()).split(":", -1);
            if (parts.length != 2) throw new IllegalStateException("format");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            // Standard AES-GCM IVs are 12 bytes and include a 16-byte tag.
            if (iv.length != 12 || ciphertext.length < 16)
                throw new IllegalStateException("format");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, iv));
            String result = new String(cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8);
            if (!valid(result)) throw new IllegalStateException("format");
            return result;
        } catch (Exception inaccessibleOrTampered) {
            // Keystore key may be invalidated or unavailable after restore.
            // Fail closed and request a new key; never log secret material.
            prefs.edit().remove(PREF_KEY).commit();
            return null;
        }
    }

    public static synchronized boolean set(Context context, String key) {
        if (Build.VERSION.SDK_INT < 23 || !valid(key)) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            String encoded = VERSION
                + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            return prefs(context).edit().putString(PREF_KEY, encoded).commit();
        } catch (Exception unavailable) {
            return false;
        }
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().remove(PREF_KEY).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry)
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            throw new IllegalStateException("Unexpected keystore entry");
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
