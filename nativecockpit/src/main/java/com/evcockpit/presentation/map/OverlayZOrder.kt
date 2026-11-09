package com.evcockpit.presentation.map

/**
 * Pure relative z-index check. Layers in MapLibre Style.getLayers() are ordered
 * bottom-to-top; other provider-owned layers may exist between our overlays.
 */
internal object OverlayZOrder {
    fun isCorrect(
        actualBottomToTop: List<String>,
        routeLayer: String,
        expectedOverlaysBottomToTop: List<String>
    ): Boolean {
        val routeIndex = actualBottomToTop.indexOf(routeLayer)
        if (routeIndex < 0) return false

        var previous = routeIndex
        for (overlay in expectedOverlaysBottomToTop) {
            val found = actualBottomToTop.indexOf(overlay)
            if (found < 0) continue // Not yet loaded from asynchronous source.
            if (found <= previous) return false
            previous = found
        }
        return true
    }
}
