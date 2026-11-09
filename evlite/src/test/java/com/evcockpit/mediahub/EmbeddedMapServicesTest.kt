package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class EmbeddedMapServicesTest {
    @Test fun boundsStayInsideTurkey(){
        assertTrue(EmbeddedMapServices.valid(37.965,34.68))
        assertTrue(EmbeddedMapServices.valid(36.9,30.7))
        assertFalse(EmbeddedMapServices.valid(52.51,13.4))
        assertFalse(EmbeddedMapServices.valid(Double.NaN,34.68))
    }
}
