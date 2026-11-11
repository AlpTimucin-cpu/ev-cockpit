package com.evcockpit.presentation.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionReadinessTest {
    private val allGranted = PermissionSnapshot(
        preciseLocationGranted = true,
        approximateLocationGranted = true,
        deviceLocationEnabled = true,
        bluetoothConnectGranted = true,
        notificationPermissionGranted = true
    )

    @Test fun fullPermissionSnapshotDoesNotProveHardwareReadiness() {
        assertTrue(allGranted.detailedNavigationReady)
        assertTrue(allGranted.missingCapabilities().isEmpty())
    }

    @Test fun approximateLocationDoesNotUnlockPreciseNavigation() {
        val snapshot = allGranted.copy(preciseLocationGranted = false)
        assertFalse(snapshot.detailedNavigationReady)
        assertTrue(snapshot.missingCapabilities().any { it.contains("yaklaşık konum") })
    }

    @Test fun missingLocationExplainsGpsLimit() {
        val snapshot = allGranted.copy(
            preciseLocationGranted = false,
            approximateLocationGranted = false
        )
        assertTrue(snapshot.missingCapabilities().any { it.contains("Konum izni yok") })
    }

    @Test fun disabledDeviceLocationBlocksNavigationEvenWithPermission() {
        val snapshot = allGranted.copy(deviceLocationEnabled = false)
        assertFalse(snapshot.detailedNavigationReady)
        assertTrue(snapshot.missingCapabilities().any { it.contains("GPS ölçümü alınamaz") })
    }

    @Test fun bluetoothDenialBlocksOnlyAdapterDependentFeatures() {
        val snapshot = allGranted.copy(bluetoothConnectGranted = false)
        assertTrue(snapshot.detailedNavigationReady)
        assertEquals(1, snapshot.missingCapabilities().size)
        assertTrue(snapshot.missingCapabilities().first().contains("ELM327"))
    }

    @Test fun notificationDenialDoesNotClaimForegroundServiceShutdown() {
        val snapshot = allGranted.copy(notificationPermissionGranted = false)
        assertTrue(snapshot.missingCapabilities().single().contains(
            "servis çalışmasını tek başına durdurmaz"
        ))
    }
}
