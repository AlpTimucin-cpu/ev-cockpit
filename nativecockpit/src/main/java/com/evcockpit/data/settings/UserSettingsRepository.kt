package com.evcockpit.data.settings

import android.content.Context
import java.io.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.evAudioSettings by preferencesDataStore(name = "ev_audio_settings_v1")

data class UserSettings(
    val isWelcomeGreetingEnabled: Boolean = true,
    val ttsVoiceName: String? = null,
    val ttsSpeechRate: Float = 1.0f,
    val isNightModeEnabled: Boolean = false,
    val isEngineSoundEnabled: Boolean = false,
    val engineSoundProfile: String = "V8",
    val engineVolume: Float = 1.0f
)

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.applicationContext.evAudioSettings

    private object Keys {
        val WELCOME = booleanPreferencesKey("welcome")
        val VOICE = stringPreferencesKey("voice")
        val RATE = floatPreferencesKey("speech_rate")
        val NIGHT = booleanPreferencesKey("night")
        val ENGINE = booleanPreferencesKey("engine")
        val PROFILE = stringPreferencesKey("engine_profile")
        val VOLUME = floatPreferencesKey("engine_volume")
    }

    val settings: Flow<UserSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { p ->
            UserSettings(
                isWelcomeGreetingEnabled = p[Keys.WELCOME] ?: true,
                ttsVoiceName = p[Keys.VOICE]?.takeIf(String::isNotBlank),
                ttsSpeechRate = (p[Keys.RATE] ?: 1f).coerceIn(.7f, 1.4f),
                isNightModeEnabled = p[Keys.NIGHT] ?: false,
                isEngineSoundEnabled = p[Keys.ENGINE] ?: false,
                engineSoundProfile = (p[Keys.PROFILE] ?: "V8")
                    .takeIf { it in ENGINE_PROFILES } ?: "V8",
                engineVolume = (p[Keys.VOLUME] ?: 1f).coerceIn(0f, 1.5f)
            )
        }

    suspend fun setWelcomeGreetingEnabled(value: Boolean) =
        store.edit { it[Keys.WELCOME] = value }

    suspend fun setTtsVoiceName(value: String?) =
        store.edit { p ->
            if (value.isNullOrBlank()) p.remove(Keys.VOICE)
            else p[Keys.VOICE] = value.take(150)
        }

    suspend fun setTtsSpeechRate(value: Float) =
        store.edit { it[Keys.RATE] = value.coerceIn(.7f, 1.4f) }

    suspend fun setNightModeEnabled(value: Boolean) =
        store.edit { it[Keys.NIGHT] = value }

    suspend fun setEngineSoundEnabled(value: Boolean) =
        store.edit { it[Keys.ENGINE] = value }

    suspend fun setEngineSoundProfile(value: String) =
        store.edit { it[Keys.PROFILE] = value.takeIf { s -> s in ENGINE_PROFILES } ?: "V8" }

    suspend fun setEngineVolume(value: Float) =
        store.edit { it[Keys.VOLUME] = value.coerceIn(0f, 1.5f) }

    companion object {
        val ENGINE_PROFILES = listOf("V8", "F1", "Futuristic", "Turbine", "Stealth")
    }
}
