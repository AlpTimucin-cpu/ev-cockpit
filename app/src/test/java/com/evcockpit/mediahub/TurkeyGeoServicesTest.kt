package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class TurkeyGeoServicesTest {
    @Test fun parsesTurkeyAddressAndRejectsAbroad() {
        val raw="""[{"display_name":"Niğde Merkez, Türkiye","lat":"37.9658","lon":"34.6781"},
           {"display_name":"Berlin","lat":"52.50","lon":"13.40"}]"""
        val data=TurkeyGeoServices.parsePlaces(raw)
        assertEquals(1,data.size)
        assertEquals(37.9658,data.first().lat,0.00001)
    }
    @Test fun stationWithTagsAndCenter() {
        val raw="""{"elements":[
            {"type":"node","id":12,"lat":37.9658,"lon":34.6781,
             "tags":{"amenity":"charging_station","operator":"Test",
             "socket:ccs":"2","socket:type2":"1"}},
            {"type":"way","id":18,"center":{"lat":36.9,"lon":30.7},
             "tags":{"amenity":"charging_station","name":"Antalya DC"}}
            ]}"""
        val data=TurkeyGeoServices.parseStations(raw)
        assertEquals(2,data.size)
        assertEquals("2",data[0].ccs)
        assertEquals("Antalya DC",data[1].title)
    }
    @Test fun stationQueryCannotScrapeWholeCountry() {
        assertFalse(TurkeyGeoServices.Bounds(35.0,25.0,43.0,46.0).isSmallEnough())
        assertTrue(TurkeyGeoServices.Bounds(37.9,34.5,38.1,34.9).isSmallEnough())
    }
}
