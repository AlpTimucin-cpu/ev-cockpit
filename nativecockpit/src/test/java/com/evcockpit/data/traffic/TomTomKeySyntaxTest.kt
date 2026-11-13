package com.evcockpit.data.traffic

import org.junit.Assert.*
import org.junit.Test

class TomTomKeySyntaxTest {
    @Test fun acceptsUnknownProviderShapeWithoutGuessingExactLength() {
        assertTrue(TomTomKeySyntax.accepted("0123456789abcdef0123456789abcdef"))
        assertTrue(TomTomKeySyntax.accepted("abc_DEF-2026"))
    }

    @Test fun refusesHeaderInjectionAndSensitiveQueryCharacters() {
        assertFalse(TomTomKeySyntax.accepted("not\r\nTomTom-Api-Key: injected"))
        assertFalse(TomTomKeySyntax.accepted("not\u0000valid"))
        assertFalse(TomTomKeySyntax.accepted("key=value&other=1"))
        assertFalse(TomTomKeySyntax.accepted("short"))
        assertFalse(TomTomKeySyntax.accepted("A".repeat(257)))
    }

    @Test fun noKeyOrDisabledConsentCannotClaimLiveTraffic() {
        assertFalse(TrafficAccess(false, false).enabled)
        assertTrue(TrafficAccess(false, true).statusTr.contains("kapalı"))
        assertTrue(TrafficAccess(false, false).statusTr.contains("kullanılamıyor"))
    }
}
