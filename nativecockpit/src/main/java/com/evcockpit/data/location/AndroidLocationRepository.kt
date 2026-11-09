package com.evcockpit.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.repository.LocationRepository

/**
 * Foreground-only Fused GPS stream; one repository is shared by
 * MapLibre, route progress and navigation speech.
 */
@Singleton
class AndroidLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationRepository {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val _gpsFix = MutableStateFlow<GpsFix?>(null)
    override val gpsFix: StateFlow<GpsFix?> = _gpsFix.asStateFlow()
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::ingest)
        }
    }

    @SuppressLint("MissingPermission") // Both permissions checked below.
    @Synchronized
    override fun start() {
        if (running) return
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if ((!fine && !coarse) || !locationManager.isLocationEnabled) {
            _gpsFix.value = null
            return
        }

        val request = LocationRequest.Builder(
            if (fine) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            1_000L
        )
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        try {
            running = true
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    synchronized(this) {
                        running = false
                        _gpsFix.value = null
                    }
                }
        } catch (_: SecurityException) {
            running = false
            _gpsFix.value = null
        }
    }

    @Synchronized
    override fun stop() {
        if (running) fused.removeLocationUpdates(callback)
        running = false
        _gpsFix.value = null
    }

    private fun ingest(location: Location) {
        @Suppress("DEPRECATION")
        val mock = if (Build.VERSION.SDK_INT >= 31) {
            location.isMock
        } else {
            location.isFromMockProvider
        }
        if (mock || !location.hasAccuracy()) return

        val fix = GpsFix(
            coordinate = GeoCoordinate(location.latitude, location.longitude),
            accuracyMeters = location.accuracy,
            recordedAtMillis = location.time,
            bearingDegrees = location.bearing.takeIf {
                location.hasBearing() && it.isFinite() && it >= 0f && it < 360f
            },
            speedMetersPerSecond = location.speed.takeIf {
                location.hasSpeed() && it.isFinite() && it >= 0f && it <= 90f
            }
        )
        acceptFix(fix)
    }

    override fun acceptFix(fix: GpsFix) {
        if (!fix.usable(System.currentTimeMillis())) return
        val previous = _gpsFix.value?.recordedAtMillis ?: 0L
        if (fix.recordedAtMillis > previous) _gpsFix.value = fix
    }
}
