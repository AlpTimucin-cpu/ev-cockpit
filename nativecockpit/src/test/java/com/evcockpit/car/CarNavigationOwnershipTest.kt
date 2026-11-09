package com.evcockpit.car

import org.junit.Assert.*
import org.junit.Test

/** Pure session arbitration: does not claim to simulate a physical head unit. */
class CarNavigationOwnershipTest {
    @Test fun oldScreenCannotReleaseNewAndroidAutoSession() {
        val firstScreen = Any()
        val replacementScreen = Any()
        CarNavigationOwnership.claim(firstScreen)
        assertTrue(CarNavigationOwnership.navigating.value)
        assertTrue(CarNavigationOwnership.owns(firstScreen))
        CarNavigationOwnership.claim(replacementScreen)
        assertTrue(CarNavigationOwnership.navigating.value)
        assertFalse(CarNavigationOwnership.owns(firstScreen))
        assertFalse(CarNavigationOwnership.release(firstScreen))
        assertTrue(CarNavigationOwnership.navigating.value)
        assertTrue(CarNavigationOwnership.owns(replacementScreen))
        assertTrue(CarNavigationOwnership.release(replacementScreen))
        assertFalse(CarNavigationOwnership.navigating.value)
    }

    @Test fun repeatedOldScreenCleanupIsIdempotent() {
        val screen = Any()
        CarNavigationOwnership.claim(screen)
        assertTrue(CarNavigationOwnership.release(screen))
        assertFalse(CarNavigationOwnership.release(screen))
        assertFalse(CarNavigationOwnership.navigating.value)
    }
}
