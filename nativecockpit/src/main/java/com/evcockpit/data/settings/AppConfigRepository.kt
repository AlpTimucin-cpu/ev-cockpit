package com.evcockpit.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evcockpit.domain.model.AppConfiguration
import com.evcockpit.domain.model.ServiceEndpointConfig
import com.evcockpit.domain.model.VehicleProfileConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.evCoreConfiguration by preferencesDataStore(name = "ev_core_configuration_v1")

/** No factory-installed vehicle measurements, SoC, battery capacity or OSRM demo URL. */
@Singleton
class AppConfigRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.applicationContext.evCoreConfiguration

    private object Keys {
        val BATTERY = stringPreferencesKey("usable_battery_kwh")
        val DC_POWER = stringPreferencesKey("peak_dc_kw")
        val CONSUMPTION = stringPreferencesKey("consumption_kwh_100km")
        val RESERVE = stringPreferencesKey("arrival_reserve_pct")
        val CONFIRMED = booleanPreferencesKey("vehicle_user_confirmed")
        val OSRM = stringPreferencesKey("osrm_https_root")
        val DEBUG_ROUTING_DEMO = booleanPreferencesKey("debug_routing_demo_optin")
        val GEOCODER = stringPreferencesKey("geocoder_https_root")
        val WEATHER = stringPreferencesKey("weather_https_root")
    }

    val configuration: Flow<AppConfiguration> = store.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            fun positive(key: androidx.datastore.preferences.core.Preferences.Key<String>): Double? =
                p[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }

            val vehicle = VehicleProfileConfig(
                usableBatteryKwh = positive(Keys.BATTERY),
                peakDcKw = positive(Keys.DC_POWER),
                consumptionKwhPer100Km = positive(Keys.CONSUMPTION),
                arrivalReservePercent = positive(Keys.RESERVE),
                userConfirmed = p[Keys.CONFIRMED] == true
            )
            val endpoints = ServiceEndpointConfig(
                osrmBaseUrl = p[Keys.OSRM]?.takeIf(String::isNotBlank),
                geocodingBaseUrl = p[Keys.GEOCODER] ?: "https://nominatim.openstreetmap.org/",
                weatherBaseUrl = p[Keys.WEATHER] ?: "https://api.open-meteo.com/",
                debugDemoRoutingEnabled = p[Keys.DEBUG_ROUTING_DEMO] == true
            )
            AppConfiguration(
                vehicle = vehicle.takeIf { it.valid() } ?: VehicleProfileConfig(),
                endpoints = endpoints.takeIf { it.validated() } ?: ServiceEndpointConfig()
            )
        }

    suspend fun saveVehicle(profile: VehicleProfileConfig) {
        require(profile.valid()) { "Geçersiz araç profili değerleri" }
        require(!profile.userConfirmed || profile.readyForEstimate()) {
            "Kullanıcı onayı için batarya, tüketim ve rezerv girilmelidir"
        }
        store.edit { p ->
            fun set(key: androidx.datastore.preferences.core.Preferences.Key<String>, v: Double?) {
                if (v == null) p.remove(key) else p[key] = v.toString()
            }
            set(Keys.BATTERY, profile.usableBatteryKwh)
            set(Keys.DC_POWER, profile.peakDcKw)
            set(Keys.CONSUMPTION, profile.consumptionKwhPer100Km)
            set(Keys.RESERVE, profile.arrivalReservePercent)
            p[Keys.CONFIRMED] = profile.userConfirmed
        }
    }

    /** Opt-in is a separate setting. Production builds ignore this switch. */
    suspend fun setDebugDemoRoutingEnabled(enabled: Boolean) {
        store.edit { p -> p[Keys.DEBUG_ROUTING_DEMO] = enabled }
    }

    suspend fun saveEndpoints(endpoints: ServiceEndpointConfig) {
        require(endpoints.validated()) { "Yalnızca geçerli HTTPS hizmet adresleri kullanılabilir" }
        store.edit { p ->
            val osrm = endpoints.osrmBaseUrl?.let {
                requireNotNull(ServiceEndpointConfig.checkedHttpsRoot(it, osrm = true))
            }
            if (osrm == null) p.remove(Keys.OSRM) else p[Keys.OSRM] = osrm
            p[Keys.GEOCODER] = requireNotNull(
                ServiceEndpointConfig.checkedHttpsRoot(endpoints.geocodingBaseUrl))
            p[Keys.WEATHER] = requireNotNull(
                ServiceEndpointConfig.checkedHttpsRoot(endpoints.weatherBaseUrl))
        }
    }
}
