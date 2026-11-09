package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test

/** The ZIP's old behavior is deliberately kept OFF the live vehicle path. */
class ZipPrototypeLabPolicyTest {
    @Test fun oldEightyPercentDemoRangeNeverClaimsToBeMeasured() {
        val candidate = ZipPrototypeLabPolicy.demoRangeKm(80.0, 18.5, 52.0)
        assertNotNull(candidate)
        assertEquals(224.86, candidate!!, 0.01)
        assertNull(ZipPrototypeLabPolicy.demoRangeKm(Double.NaN, 18.5, 52.0))
        assertNull(ZipPrototypeLabPolicy.demoRangeKm(101.0, 18.5, 52.0))
        assertNull(ZipPrototypeLabPolicy.demoRangeKm(80.0, 0.0, 52.0))
        assertNull(ZipPrototypeLabPolicy.demoRangeKm(80.0, 18.5, -1.0))
    }

    @Test fun offline028cCandidateReproducesBadPrototypeScaleButRejectsMalformedHex() {
        assertEquals(50.0,
            ZipPrototypeLabPolicy.unverified028cFromTypedPayload("62028C007D")!!, 0.001)
        assertEquals(50.0,
            ZipPrototypeLabPolicy.unverified028cFromTypedPayload("62 02 8C 00 7d")!!, 0.001)
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload("62028C00FF"))
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload("62028C00"))
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload("61028C007D"))
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload("62028C00ZZ"))
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload("22028C"))
        assertNull(ZipPrototypeLabPolicy.unverified028cFromTypedPayload(
            "62028C007D\r2E028C0001"
        ))
    }

    @Test fun unsafeCommandClassifierDemonstratesOldPrefixOnlyFlaw() {
        assertTrue(ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand("22028C"))
        assertTrue(ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand("22\r2E028C"))
        assertTrue(ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand("01F"))
        assertFalse(ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand("2E028C"))
        assertFalse(ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand("ATSH710"))
    }

    @Test fun passengerBypassCanOnlyBeDescribedNotUsedForPlayback() {
        assertTrue(ZipPrototypeLabPolicy.prototypeWouldPermitVideo(0.0, false))
        assertFalse(ZipPrototypeLabPolicy.prototypeWouldPermitVideo(50.0, false))
        assertTrue(ZipPrototypeLabPolicy.prototypeWouldPermitVideo(50.0, true))
        assertFalse(ZipPrototypeLabPolicy.prototypeWouldPermitVideo(Double.NaN, true))
        assertFalse(ZipPrototypeLabPolicy.prototypeWouldPermitVideo(-1.0, true))
    }
}
