package com.evcockpit.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix

/**
 * Foreground Car Screen GPS only: no background tracking.
 * Subscriptions have unique callbacks and epochs. A delayed failure or location
 * from an older session cannot clear or fill the newer car-screen GPS reading.
 */
internal class CarGpsTracker(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val mutableFix = MutableStateFlow<GpsFix?>(null)
    val gpsFix: StateFlow<GpsFix?> = mutableFix
    private val gate = GpsSessionGate()
    private var callback: LocationCallback? = null
    private var started = false

    @SuppressLint("MissingPermission") // checked immediately before request
    fun start() {
        if (started) return
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            mutableFix.value = null
            return
        }
        val token = gate.open()
        val receiver = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!started || !gate.isCurrent(token)) return
                for (location in result.locations) {
                    if (!gate.isCurrent(token)) return
                    update(location)
                }
            }
        }
        callback = receiver
        started = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            client.requestLocationUpdates(request, receiver, Looper.getMainLooper())
                .addOnFailureListener {
                    if (gate.isCurrent(token)) stop()
                }
        } catch (_: SecurityException) {
            if (gate.isCurrent(token)) stop()
        } catch (_: RuntimeException) {
            if (gate.isCurrent(token)) stop()
        }
    }

    private fun update(location: Location) {
        if (LocationCompat.isMock(location) || !location.hasAccuracy()) return
        val fix = GpsFix(
            coordinate = GeoCoordinate(location.latitude, location.longitude),
            accuracyMeters = location.accuracy,
            recordedAtMillis = location.time,
            bearingDegrees = location.bearing.takeIf {
                location.hasBearing() && it.isFinite()
            },
            speedMetersPerSecond = location.speed.takeIf {
                location.hasSpeed() && it.isFinite()
            }
        ).takeIf { it.usable(System.currentTimeMillis()) }
        if (fix != null &&
            fix.recordedAtMillis > (mutableFix.value?.recordedAtMillis ?: 0L)
        ) mutableFix.value = fix
    }

    fun stop() {
        gate.invalidate() // invalidate BEFORE unsubscribing asynchronous callbacks
        val old = callback
        callback = null
        started = false
        mutableFix.value = null
        if (old != null) runCatching { client.removeLocationUpdates(old) }
    }
}
