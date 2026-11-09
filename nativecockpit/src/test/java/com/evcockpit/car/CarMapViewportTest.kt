package com.evcockpit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMapViewportTest {
    @Test
    fun largeHostInsetsAvoidTemplateOverlay() {
        val viewport = CarMapViewport(
            widthPx = 1920, heightPx = 1080, dpi = 160,
            visible = CarMapArea(420, 40, 1920, 1040),
            stable = CarMapArea(460, 90, 1880, 980)
        )
        assertEquals(CarMapArea(460, 90, 1880, 980), viewport.visibleSafeArea())
        val padding = viewport.cameraInsets()!!
        assertTrue(padding.left >= 460)
        assertTrue(padding.top >= 90)
        assertTrue(padding.right >= 40)
        assertTrue(padding.bottom >= 100)
        assertTrue(padding.left + padding.right < 1920)
        assertTrue(padding.top + padding.bottom < 1080)
    }

    @Test
    fun smallerHostNeverUsesHardcodedTwelveInchResolution() {
        val viewport = CarMapViewport(
            800, 480, 160,
            visible = CarMapArea(0, 56, 800, 480),
            stable = CarMapArea(0, 56, 660, 480)
        )
        val padding = viewport.cameraInsets()!!
        assertTrue(padding.right >= 140)
        assertTrue(padding.top >= 56)
        assertTrue(800 - padding.left - padding.right > 0)
        assertTrue(480 - padding.top - padding.bottom > 0)
    }

    @Test
    fun surfaceDpiChangesStyleAndCameraMargins() {
        val normal = CarMapViewport(1280, 720, 160)
        val high = CarMapViewport(1280, 720, 240)
        assertTrue(high.stylePixels(9f) > normal.stylePixels(9f))
        assertTrue(high.cameraInsets()!!.left > normal.cameraInsets()!!.left)
        assertNotEquals(high.cameraInsets(), normal.cameraInsets())
    }

    @Test
    fun emptyOrOutsideRectsDoNotHideAllGeometry() {
        val viewport = CarMapViewport(
            800, 480, 160,
            visible = CarMapArea(0, 0, 0, 0),
            stable = CarMapArea(-100, -80, 1000, 600)
        )
        assertEquals(CarMapArea(0, 0, 800, 480), viewport.visibleSafeArea())
        assertNotNull(viewport.cameraInsets())
    }

    @Test
    fun excessiveOcclusionDoesNotRequestImpossibleCameraUpdate() {
        val viewport = CarMapViewport(
            800, 480, 160,
            stable = CarMapArea(790, 470, 800, 480)
        )
        assertNull(viewport.cameraInsets())
    }

    @Test
    fun visibleAreaIsUsedWhenStableIsUnknown() {
        val viewport = CarMapViewport(
            1280, 720, 160,
            visible = CarMapArea(120, 80, 1100, 680)
        )
        assertEquals(CarMapArea(120, 80, 1100, 680), viewport.visibleSafeArea())
        assertTrue(viewport.cameraInsets()!!.left >= 120)
    }
}
