package com.evcockpit.presentation.voice

import android.content.Context

/** Only an explicit driver preference. No default route or simulated TTS. */
class VoicePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eya_voice_v1",Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled",true)
        set(v) { prefs.edit().putBoolean("enabled",v).apply() }
    var volume: Float
        get() = prefs.getFloat("volume",.8f).coerceIn(.15f,1f)
        set(v) { prefs.edit().putFloat("volume",v.coerceIn(.15f,1f)).apply() }
}
